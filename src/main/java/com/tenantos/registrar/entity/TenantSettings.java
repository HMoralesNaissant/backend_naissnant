package com.tenantos.registrar.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * JPA entity for the tenant_settings table (V3 migration). Keyed by tenant_id rather than a
 * surrogate id - it is 1:1 with the tenant, which also makes the provisioning step that creates it
 * idempotent without any extra checking.
 */
@Entity
@Table(name = "tenant_settings")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class TenantSettings extends BaseAuditFields {

  @Id
  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  @Column(name = "timezone", length = 64, nullable = false)
  @Default
  private String timezone = "UTC";

  @Column(name = "locale", length = 10, nullable = false)
  @Default
  private String locale = "en-US";

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "branding", nullable = false)
  @Default
  private String branding = "{}";

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "features", nullable = false)
  @Default
  private String features = "{}";
}
