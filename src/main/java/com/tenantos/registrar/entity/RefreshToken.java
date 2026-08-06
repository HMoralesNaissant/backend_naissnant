package com.tenantos.registrar.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the refresh_tokens table (V3 migration).
 *
 * <p>Only the SHA-256 hash of the token is stored, the same store-the-hash-not-the-secret approach
 * as onboarding_token - a database leak must not hand out usable refresh tokens.
 *
 * <p>{@link #rotatedTo} chains a token to its successor. That chain is what makes reuse detection
 * possible: a client presenting a token that has already been rotated is presenting a copy, which
 * means the token was captured, so the whole chain is revoked rather than served.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class RefreshToken extends BaseAuditFields {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "token_hash", length = 64, nullable = false, unique = true)
  private String tokenHash;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "session_id")
  private UUID sessionId;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "rotated_to")
  private UUID rotatedTo;

  @Column(name = "device_fingerprint", length = 128)
  private String deviceFingerprint;

  @Column(name = "ip_address", length = 45)
  private String ipAddress;

  @Column(name = "user_agent", length = 512)
  private String userAgent;

  public boolean isLive(Instant now) {
    return revokedAt == null && rotatedTo == null && expiresAt.isAfter(now);
  }
}
