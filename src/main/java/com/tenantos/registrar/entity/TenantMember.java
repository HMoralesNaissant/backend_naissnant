package com.tenantos.registrar.entity;

import com.tenantos.registrar.enums.MembershipStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the tenant_members table (V3 migration): a user's membership of a tenant.
 *
 * <p>The (tenant_id, user_id) unique constraint is the concurrency guard on provisioning - a
 * duplicated registration callback cannot produce a second membership.
 */
@Entity
@Table(
    name = "tenant_members",
    uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "user_id"}))
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class TenantMember extends BaseAuditFields {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  /** Denormalized primary role name; the authoritative grants live in user_roles. */
  @Column(name = "role", length = 30, nullable = false)
  @Default
  private String role = "MEMBER";

  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 20, nullable = false)
  @Default
  private MembershipStatus status = MembershipStatus.ACTIVE;

  /** Null for the registering user, who invited nobody - they created the tenant. */
  @Column(name = "invited_by")
  private UUID invitedBy;

  @Column(name = "joined_at")
  private Instant joinedAt;
}
