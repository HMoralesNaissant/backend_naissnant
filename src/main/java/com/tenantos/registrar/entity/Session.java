package com.tenantos.registrar.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the sessions table (V3 migration).
 *
 * <p>Access tokens are stateless JWTs and cannot be withdrawn once signed. This row is the
 * server-side handle that makes logout and forced sign-out real: the token stays cryptographically
 * valid, but the session it names is revoked.
 */
@Entity
@Table(name = "sessions")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class Session extends BaseAuditFields {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "login_at", nullable = false)
  @Default
  private Instant loginAt = Instant.now();

  @Column(name = "last_activity_at", nullable = false)
  @Default
  private Instant lastActivityAt = Instant.now();

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "ip_address", length = 45)
  private String ipAddress;

  @Column(name = "user_agent", length = 512)
  private String userAgent;

  @Column(name = "device_fingerprint", length = 128)
  private String deviceFingerprint;

  public boolean isLive(Instant now) {
    return revokedAt == null && expiresAt.isAfter(now);
  }
}
