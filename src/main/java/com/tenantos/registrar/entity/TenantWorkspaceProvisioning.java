package com.tenantos.registrar.entity;

import com.tenantos.registrar.enums.ProvisioningStep;
import com.tenantos.registrar.enums.TenantsRegistrationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/**
 * JPA entity mapping for the tenant_workspace_provisioning table (V2 migration): the durable
 * job record for creating a tenant's Kubernetes workspace. Inserted in the same transaction as
 * the tenants_registration row so the job can never be lost, then claimed and executed
 * out-of-band by TenantWorkspaceProvisioningService.
 *
 * <p>company_email is UNIQUE, so a tenant has exactly one job row for the lifetime of the
 * account - retries mutate this row rather than inserting new ones.
 */
@Entity
@Table(name = "tenant_workspace_provisioning")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class TenantWorkspaceProvisioning extends BaseAuditFields {

  @Id
  @Column(name = "provisioning_id", length = 64, nullable = false)
  private String provisioningId;

  @Column(name = "company_email", length = 200, nullable = false, unique = true)
  private String companyEmail;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 30, nullable = false)
  @Default
  private TenantsRegistrationStatus status = TenantsRegistrationStatus.WORKSPACE_PENDING;

  /**
   * Where the pipeline resumes. Each database-backed step advances this in the same transaction as
   * its own work, so a retry re-enters at the step that failed rather than replaying the ones that
   * already committed.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "current_step", length = 40, nullable = false)
  @Default
  private ProvisioningStep currentStep = ProvisioningStep.CREATE_USER;

  /** The Kubernetes namespace actually provisioned; null until the job succeeds. */
  @Column(name = "namespace", length = 63)
  private String namespace;

  @Column(name = "attempts", nullable = false)
  @Default
  private int attempts = 0;

  @Column(name = "max_attempts", nullable = false)
  @Default
  private int maxAttempts = 5;

  /** Earliest time the poller may claim this row; pushed out by exponential backoff on failure. */
  @Column(name = "next_attempt_at", nullable = false)
  @Default
  private Instant nextAttemptAt = Instant.now();

  /**
   * When the current attempt was claimed. Doubles as the lease: a row still IN_PROGRESS long
   * after this was set belongs to a worker that died, and is fair game to re-claim.
   */
  @Column(name = "claimed_at")
  private Instant claimedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  // Diagnostic only - never surfaced over the API, it can carry AWS/EKS internals.
  @Column(name = "last_error", columnDefinition = "TEXT")
  private String lastError;
}
