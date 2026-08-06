package com.tenantos.registrar.entity;

import com.tenantos.registrar.enums.AuditEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the audit_logs table (V3 migration): append-only business events.
 *
 * <p>Not extending BaseAuditFields - rows here are never modified, so there is no updated_at.
 * Separate from {@link LoginAudit}, which is security-specific and queried on different axes.
 */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "tenant_id")
  private UUID tenantId;

  /** Null when the actor is the system - e.g. events written by the provisioning pipeline. */
  @Column(name = "actor_user_id")
  private UUID actorUserId;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", length = 60, nullable = false)
  private AuditEventType eventType;

  @Column(name = "resource_type", length = 60)
  private String resourceType;

  @Column(name = "resource_id", length = 64)
  private String resourceId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false)
  @Builder.Default
  private String payload = "{}";

  @Column(name = "ip_address", length = 45)
  private String ipAddress;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();
}
