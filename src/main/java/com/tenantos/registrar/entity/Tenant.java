package com.tenantos.registrar.entity;

import com.tenantos.registrar.enums.TenantStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
 * JPA entity for the tenants table (V3 migration): the tenant root that everything else in the
 * model hangs off.
 */
@Entity
@Table(name = "tenants")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class Tenant extends BaseAuditFields {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "name", length = 200, nullable = false)
  private String name;

  /**
   * URL-safe identifier, unique across all tenants. Capped at 63 characters because it is also
   * used verbatim as the tenant's Kubernetes namespace suffix ({@code tenant-<slug>}), and a
   * namespace name must be a valid RFC 1123 DNS label.
   */
  @Column(name = "slug", length = 63, nullable = false, unique = true)
  private String slug;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 20, nullable = false)
  @Default
  private TenantStatus status = TenantStatus.ACTIVE;

  @Column(name = "plan", length = 30, nullable = false)
  @Default
  private String plan = "FREE_TRIAL";

  @Column(name = "timezone", length = 64, nullable = false)
  @Default
  private String timezone = "UTC";

  @Column(name = "locale", length = 10, nullable = false)
  @Default
  private String locale = "en-US";

  // JSON kept as a String, matching how Onboarding.otpDetails and OnboardingToken.clientDetails
  // already map their JSON columns in this codebase.
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata", nullable = false)
  @Default
  private String metadata = "{}";
}
