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

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the tenant_namespace table (V6 migration): where a tenant's Kubernetes workspace
 * actually lives. Written by the CREATE_NAMESPACE provisioning step.
 *
 * <p>Keyed by tenant_id rather than a surrogate id, the same way {@link TenantSettings} is - it is
 * 1:1 with the tenant, which is what lets the step re-run after a failure and upsert this row
 * instead of appending a duplicate.
 *
 * <p>Separate from {@code tenant_workspace_provisioning.namespace}, which is a scratch field on a
 * queue record: the job exists to be claimed and retried, whereas "this tenant is on namespace X of
 * cluster Y" outlives it.
 */
@Entity
@Table(name = "tenant_namespace")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class TenantNamespace extends BaseAuditFields {

  @Id
  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  @Column(name = "namespace", length = 63, nullable = false, unique = true)
  private String namespace;

  /** Null until the real EKS call is enabled - the stubbed step has no cluster to name. */
  @Column(name = "cluster_name", length = 100)
  private String clusterName;

  @Column(name = "cluster_endpoint", length = 255)
  private String clusterEndpoint;

  @Column(name = "aws_region", length = 30)
  private String awsRegion;

  /**
   * When the namespace was last applied. Distinct from created_at because server-side apply is
   * idempotent and re-runs on retry: this moves forward each time, created_at pins the first.
   */
  @Column(name = "applied_at", nullable = false)
  @Default
  private Instant appliedAt = Instant.now();
}
