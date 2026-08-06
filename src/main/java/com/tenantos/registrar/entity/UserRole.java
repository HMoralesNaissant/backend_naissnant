package com.tenantos.registrar.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the user_roles table (V3 migration): which roles a user holds.
 *
 * <p>Carries tenant_id even though it is reachable through the role, so answering "what may this
 * user do in this tenant?" is one indexed lookup rather than a join back through roles.
 */
@Entity
@Table(name = "user_roles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRole {

  @EmbeddedId private UserRoleId id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  /** Null when provisioning assigns the owner role - nobody granted it, registration did. */
  @Column(name = "assigned_by")
  private UUID assignedBy;

  @Column(name = "assigned_at", nullable = false)
  @Builder.Default
  private Instant assignedAt = Instant.now();

  @Embeddable
  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @EqualsAndHashCode
  public static class UserRoleId implements Serializable {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;
  }
}
