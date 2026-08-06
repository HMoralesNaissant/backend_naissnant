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

import java.util.UUID;

/**
 * JPA entity for the permissions table (V3 migration): the global {@code resource:action}
 * vocabulary, seeded once by the migration rather than per tenant. Tenancy lives in {@link Role};
 * these rows are shared by every tenant's roles through role_permissions.
 */
@Entity
@Table(name = "permissions")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class Permission extends BaseAuditFields {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** {@code resource:action}, e.g. {@code orders:read}. Unique across the catalog. */
  @Column(name = "code", length = 80, nullable = false, unique = true)
  private String code;

  @Column(name = "resource", length = 50, nullable = false)
  private String resource;

  @Column(name = "action", length = 20, nullable = false)
  private String action;

  @Column(name = "description", length = 255)
  private String description;
}
