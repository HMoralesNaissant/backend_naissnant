package com.tenantos.registrar.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the api_keys table (V3 migration).
 *
 * <p>{@link #keyHash} is the SHA-256 of the full key and the plaintext is never persisted;
 * {@link #keyPrefix} is the short displayable fragment so a key can be identified in a UI without
 * being revealed.
 */
@Entity
@Table(name = "api_keys", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "name"}))
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class ApiKey extends BaseAuditFields {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "name", length = 100, nullable = false)
  private String name;

  @Column(name = "key_prefix", length = 16, nullable = false)
  private String keyPrefix;

  @Column(name = "key_hash", length = 64, nullable = false, unique = true)
  private String keyHash;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "scopes", nullable = false)
  @Default
  private String scopes = "[]";

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;
}
