package com.tenantos.registrar.services;

import com.tenantos.registrar.entity.OnboardingToken;
import com.tenantos.registrar.exceptions.InvalidOnboardingTokenException;
import com.tenantos.registrar.repository.OnboardingTokenRepository;
import com.tenantos.registrar.utils.HashUtils;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@RequiredArgsConstructor
@Service
public class OnboardingOnFlightTokenService {

  @Value("${fe.onboarding.session-token-cookie-name}")
  @Getter
  private String onboardingSessionTokenCookieName;

  @Value("${fe.onboarding.token-ttl-seconds}")
  @Getter
  private long ttlSeconds;

  private final SecureRandom secureRandom = new SecureRandom();
  private final OnboardingTokenRepository onboardingTokenRepository;
  private final ObjectMapper objectMapper;

  /**
   * Issues a new preflight token and logs the requesting client's browser details (whatever's
   * non-null) as a JSON blob, so new fields can be captured later without a schema change.
   */
  @Transactional
  public String issue(Map<String, Object> clientDetails) {
    byte[] raw = new byte[32];
    secureRandom.nextBytes(raw);
    String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    String tokenHash = hash(rawToken);

    Instant now = Instant.now();
    OnboardingToken token =
        OnboardingToken.builder()
            .tokenHash(tokenHash)
            .clientDetails(toJson(clientDetails))
            .expiresAt(now.plusSeconds(ttlSeconds))
            .build();
    onboardingTokenRepository.save(token);

    return rawToken;
  }

  @Transactional
  public void validateAndConsume(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) {
      throw new InvalidOnboardingTokenException("Missing onboarding token");
    }
    int updated = onboardingTokenRepository.consume(hash(rawToken), Instant.now());
    if (updated != 1) {
      throw new InvalidOnboardingTokenException(
          "Onboarding token is invalid, expired, or already used");
    }
  }

  /**
   * Shared cookie-extraction logic so the configurable cookie name only lives here, not duplicated
   * across the controller and OnboardingTokenAuthenticationFilter.
   */
  public String extractToken(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (onboardingSessionTokenCookieName.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }

  /**
   * Read-only check used by OnboardingTokenAuthenticationFilter to gate every request under
   * /onboarding. Deliberately doesn't consume the token — burning it on the actual write happens
   * via validateAndConsume, so a non-mutating endpoint checked only by this method (if one is ever
   * added under /onboarding) can't drain it.
   */
  @Transactional(readOnly = true)
  public boolean isValid(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) {
      return false;
    }
    Instant now = Instant.now();
    return onboardingTokenRepository
        .findById(hash(rawToken))
        .filter(token -> token.getUsedAt() == null && token.getExpiresAt().isAfter(now))
        .isPresent();
  }

  private static String hash(String rawToken) {
    return HashUtils.sha256Hex(rawToken);
  }

  private String toJson(Map<String, Object> clientDetails) {
    try {
      return objectMapper.writeValueAsString(clientDetails);
    } catch (Exception e) {
      return "{}";
    }
  }
}
