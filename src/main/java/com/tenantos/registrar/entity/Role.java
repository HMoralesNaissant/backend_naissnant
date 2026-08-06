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

import java.util.UUID;

/**
 * JPA entity for the roles table (V3 migration). Tenant-scoped, so each tenant owns its copy of
 * the five system roles and can add its own alongside them.
 */
@Entity
@Table(name = "roles", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "name"}))
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class Role extends BaseAuditFields {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "name", length = 50, nullable = false)
  private String name;

  @Column(name = "description", length = 255)
  private String description;

  /** True for the roles provisioning seeds; false for anything a tenant creates later. */
  @Column(name = "is_system", nullable = false)
  @Default
  private boolean systemRole = true;
}
