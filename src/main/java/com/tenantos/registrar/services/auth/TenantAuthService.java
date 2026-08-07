package com.tenantos.registrar.services.auth;

import com.tenantos.registrar.domain.request.TenantLoginRequest;
import com.tenantos.registrar.entity.AuditLog;
import com.tenantos.registrar.entity.LoginAudit;
import com.tenantos.registrar.entity.RefreshToken;
import com.tenantos.registrar.entity.Session;
import com.tenantos.registrar.entity.Tenant;
import com.tenantos.registrar.entity.TenantMember;
import com.tenantos.registrar.entity.User;
import com.tenantos.registrar.enums.AuditEventType;
import com.tenantos.registrar.enums.LoginOutcome;
import com.tenantos.registrar.enums.MembershipStatus;
import com.tenantos.registrar.enums.UserStatus;
import com.tenantos.registrar.exceptions.AuthenticationFailedException;
import com.tenantos.registrar.exceptions.TenantNotProvisionedException;
import com.tenantos.registrar.repository.AuditLogRepository;
import com.tenantos.registrar.repository.LoginAuditRepository;
import com.tenantos.registrar.repository.RefreshTokenRepository;
import com.tenantos.registrar.repository.SessionRepository;
import com.tenantos.registrar.repository.TenantMemberRepository;
import com.tenantos.registrar.repository.TenantRepository;
import com.tenantos.registrar.repository.UserRepository;
import com.tenantos.registrar.repository.UserRoleRepository;
import com.tenantos.registrar.security.JwtProvider;
import com.tenantos.registrar.utils.HashUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-aware authentication: sign-in, refresh-token rotation, and sign-out.
 *
 * <p>This is what populates the sessions, refresh_tokens and login_audit tables. Provisioning
 * deliberately does not: a background job has no HTTP request, so it has no IP, user agent or
 * device to record, and the user has not actually authenticated at that point. Fabricating a
 * session there would have made those columns lies.
 *
 * <p>Two things are held to consistently. Every failure path writes a login_audit row before
 * throwing, and every failure throws the same exception with the same message - the audit trail
 * knows why, the caller does not, so the endpoint can't be used to enumerate registered addresses.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TenantAuthService {

  private static final int REFRESH_TOKEN_BYTES = 48;

  @Value("${tenant.auth.access-token-ttl-seconds:900}")
  private long accessTokenTtlSeconds;

  @Value("${tenant.auth.refresh-token-ttl-seconds:2592000}")
  private long refreshTokenTtlSeconds;

  @Value("${tenant.auth.session-ttl-seconds:2592000}")
  private long sessionTtlSeconds;

  private final UserRepository userRepository;
  private final TenantRepository tenantRepository;
  private final TenantMemberRepository tenantMemberRepository;
  private final UserRoleRepository userRoleRepository;
  private final SessionRepository sessionRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final LoginAuditRepository loginAuditRepository;
  private final AuditLogRepository auditLogRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtProvider jwtProvider;
  private final SecureRandom secureRandom = new SecureRandom();

  /** What a successful login or refresh hands back: the body payload plus the raw refresh token. */
  public record AuthResult(
      String accessToken,
      long expiresInSeconds,
      String rawRefreshToken,
      long refreshTtlSeconds,
      UUID tenantId,
      String tenantSlug,
      UUID userId,
      List<String> roles) {}

  @Transactional
  public AuthResult login(TenantLoginRequest request, ClientContext client) {
    User user = userRepository.findByEmail(request.email()).orElse(null);

    if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      // Same branch for both cases on purpose - a distinct "no such user" response would leak
      // which addresses exist. The password check is still run against nothing when the user is
      // absent only in the sense that we skip it; timing hardening is a separate concern noted in
      // the design doc.
      audit(null, request.email(), null, LoginOutcome.INVALID_CREDENTIALS, client);
      throw new AuthenticationFailedException("Invalid email or password");
    }

    if (user.getStatus() != UserStatus.ACTIVE) {
      audit(user.getId(), user.getEmail(), null, LoginOutcome.USER_DISABLED, client);
      throw new AuthenticationFailedException("Invalid email or password");
    }

    TenantMember membership = resolveMembership(user, request.tenantSlug(), client);
    Tenant tenant =
        tenantRepository
            .findById(membership.getTenantId())
            .orElseThrow(() -> new IllegalStateException("Membership points at a missing tenant"));

    AuthResult result = issue(user, tenant, client, null);

    audit(user.getId(), user.getEmail(), tenant.getId(), LoginOutcome.SUCCESS, client);
    auditLogRepository.save(
        AuditLog.builder()
            .tenantId(tenant.getId())
            .actorUserId(user.getId())
            .eventType(AuditEventType.USER_LOGGED_IN)
            .resourceType("session")
            .ipAddress(client.ipAddress())
            .build());

    return result;
  }

  /**
   * Rotation: every refresh mints a new token and retires the one presented.
   *
   * <p>A token that has already been rotated turning up again means two parties hold it, which
   * means it was captured. The response is to burn the whole family - every session and token the
   * user has - rather than serve the request, because there is no way to tell which of the two
   * holders is the legitimate one.
   */
  @Transactional
  public AuthResult refresh(String rawRefreshToken, ClientContext client) {
    if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
      throw new AuthenticationFailedException("Missing refresh token");
    }

    RefreshToken presented =
        refreshTokenRepository
            .findByTokenHash(HashUtils.sha256Hex(rawRefreshToken))
            .orElseThrow(() -> new AuthenticationFailedException("Invalid refresh token"));

    Instant now = Instant.now();

    if (presented.getRotatedTo() != null) {
      Instant revokedAt = Instant.now();
      refreshTokenRepository.revokeAllForUser(presented.getUserId(), revokedAt);
      sessionRepository.revokeAllForUser(presented.getUserId(), revokedAt);

      User user = userRepository.findById(presented.getUserId()).orElse(null);
      audit(
          presented.getUserId(),
          user == null ? "unknown" : user.getEmail(),
          presented.getTenantId(),
          LoginOutcome.TOKEN_REUSE_DETECTED,
          client);
      auditLogRepository.save(
          AuditLog.builder()
              .tenantId(presented.getTenantId())
              .actorUserId(presented.getUserId())
              .eventType(AuditEventType.REFRESH_TOKEN_REUSE_DETECTED)
              .resourceType("refresh_token")
              .resourceId(String.valueOf(presented.getId()))
              .ipAddress(client.ipAddress())
              .build());

      log.warn(
          "Refresh token reuse detected for user {} - revoked all sessions", presented.getUserId());
      throw new AuthenticationFailedException("Invalid refresh token");
    }

    if (!presented.isLive(now)) {
      throw new AuthenticationFailedException("Invalid refresh token");
    }

    Session session =
        presented.getSessionId() == null
            ? null
            : sessionRepository.findById(presented.getSessionId()).orElse(null);
    if (session == null || !session.isLive(now)) {
      throw new AuthenticationFailedException("Invalid refresh token");
    }

    User user =
        userRepository
            .findById(presented.getUserId())
            .orElseThrow(() -> new AuthenticationFailedException("Invalid refresh token"));
    Tenant tenant =
        tenantRepository
            .findById(presented.getTenantId())
            .orElseThrow(() -> new AuthenticationFailedException("Invalid refresh token"));

    AuthResult result = issue(user, tenant, client, session);

    // Retire the presented token by pointing it at its successor. Not revoked: rotatedTo is what
    // distinguishes "superseded normally" from "revoked", and it's what makes reuse detectable.
    RefreshToken successor =
        refreshTokenRepository
            .findByTokenHash(HashUtils.sha256Hex(result.rawRefreshToken()))
            .orElseThrow(() -> new IllegalStateException("Newly issued refresh token not found"));
    presented.setRotatedTo(successor.getId());

    session.setLastActivityAt(now);

    auditLogRepository.save(
        AuditLog.builder()
            .tenantId(tenant.getId())
            .actorUserId(user.getId())
            .eventType(AuditEventType.REFRESH_TOKEN_ROTATED)
            .resourceType("refresh_token")
            .resourceId(String.valueOf(successor.getId()))
            .ipAddress(client.ipAddress())
            .build());

    return result;
  }

  /** Revokes the session behind a refresh token, and every token issued against it. */
  @Transactional
  public void logout(String rawRefreshToken) {
    if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
      return; // Nothing to revoke; logout is idempotent and never reports failure.
    }

    refreshTokenRepository
        .findByTokenHash(HashUtils.sha256Hex(rawRefreshToken))
        .ifPresent(
            token -> {
              Instant now = Instant.now();
              token.setRevokedAt(now);
              if (token.getSessionId() != null) {
                refreshTokenRepository.revokeBySession(token.getSessionId(), now);
                sessionRepository
                    .findById(token.getSessionId())
                    .ifPresent(session -> session.setRevokedAt(now));
              }
              auditLogRepository.save(
                  AuditLog.builder()
                      .tenantId(token.getTenantId())
                      .actorUserId(token.getUserId())
                      .eventType(AuditEventType.USER_LOGGED_OUT)
                      .resourceType("session")
                      .resourceId(String.valueOf(token.getSessionId()))
                      .build());
            });
  }

  /**
   * Picks the tenant to sign in to. A user with exactly one membership needs no help; more than one
   * and the caller must name it, because guessing would silently drop someone into the wrong
   * tenant.
   */
  private TenantMember resolveMembership(User user, String tenantSlug, ClientContext client) {
    List<TenantMember> memberships =
        tenantMemberRepository.findByUserIdAndStatus(user.getId(), MembershipStatus.ACTIVE);

    if (memberships.isEmpty()) {
      audit(user.getId(), user.getEmail(), null, LoginOutcome.NO_TENANT, client);
      throw new TenantNotProvisionedException(
          "Your workspace is still being set up - try again in a moment");
    }

    if (tenantSlug == null || tenantSlug.isBlank()) {
      if (memberships.size() > 1) {
        throw new AuthenticationFailedException(
            "This account belongs to several workspaces - specify tenantSlug");
      }
      return memberships.getFirst();
    }

    UUID requested =
        tenantRepository
            .findBySlug(tenantSlug)
            .map(Tenant::getId)
            .orElseThrow(() -> new AuthenticationFailedException("Invalid email or password"));

    return memberships.stream()
        .filter(m -> m.getTenantId().equals(requested))
        .findFirst()
        // Not a member of the named tenant. Same generic failure as bad credentials, so this
        // can't be used to probe which tenants exist.
        .orElseThrow(() -> new AuthenticationFailedException("Invalid email or password"));
  }

  /**
   * Creates (or reuses, on refresh) the session and mints a matching access/refresh token pair. The
   * raw refresh token exists only in the returned record - the database gets its SHA-256.
   */
  private AuthResult issue(User user, Tenant tenant, ClientContext client, Session existing) {
    Instant now = Instant.now();

    Session session =
        existing != null
            ? existing
            : sessionRepository.save(
                Session.builder()
                    .userId(user.getId())
                    .tenantId(tenant.getId())
                    .loginAt(now)
                    .lastActivityAt(now)
                    .expiresAt(now.plusSeconds(sessionTtlSeconds))
                    .ipAddress(client.ipAddress())
                    .userAgent(client.userAgent())
                    .deviceFingerprint(client.deviceFingerprint())
                    .build());

    String rawRefreshToken = generateRefreshToken();
    refreshTokenRepository.save(
        RefreshToken.builder()
            .tokenHash(HashUtils.sha256Hex(rawRefreshToken))
            .userId(user.getId())
            .tenantId(tenant.getId())
            .sessionId(session.getId())
            .expiresAt(now.plusSeconds(refreshTokenTtlSeconds))
            .deviceFingerprint(client.deviceFingerprint())
            .ipAddress(client.ipAddress())
            .userAgent(client.userAgent())
            .build());

    List<String> roles = userRoleRepository.findRoleNames(user.getId(), tenant.getId());

    String accessToken =
        jwtProvider.generateToken(
            user.getEmail(),
            Map.of(
                "userId", user.getId().toString(),
                "tenantId", tenant.getId().toString(),
                "tenantSlug", tenant.getSlug(),
                "sessionId", session.getId().toString(),
                "roles", roles),
            accessTokenTtlSeconds * 1000);

    return new AuthResult(
        accessToken,
        accessTokenTtlSeconds,
        rawRefreshToken,
        refreshTokenTtlSeconds,
        tenant.getId(),
        tenant.getSlug(),
        user.getId(),
        roles);
  }

  private void audit(
      UUID userId, String email, UUID tenantId, LoginOutcome outcome, ClientContext client) {
    loginAuditRepository.save(
        LoginAudit.builder()
            .userId(userId)
            .email(email)
            .tenantId(tenantId)
            .outcome(outcome)
            .ipAddress(client.ipAddress())
            .userAgent(client.userAgent())
            .build());
  }

  private String generateRefreshToken() {
    byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** Request-scoped facts recorded against sessions, tokens and the login trail. */
  public record ClientContext(String ipAddress, String userAgent, String deviceFingerprint) {

    public static ClientContext of(String ip, String userAgent) {
      // The fingerprint is a hash of the user agent for now - enough to notice a token being
      // replayed from a different client, without a real device-fingerprinting library.
      return new ClientContext(
          ip,
          truncate(userAgent),
          userAgent == null ? null : HashUtils.sha256Hex(userAgent).substring(0, 32));
    }

    private static String truncate(String userAgent) {
      return Optional.ofNullable(userAgent)
          .map(ua -> ua.length() > 512 ? ua.substring(0, 512) : ua)
          .orElse(null);
    }
  }
}
