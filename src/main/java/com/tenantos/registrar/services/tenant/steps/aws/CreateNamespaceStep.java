package com.tenantos.registrar.services.tenant.steps.aws;

import com.tenantos.registrar.entity.AuditLog;
import com.tenantos.registrar.entity.Tenant;
import com.tenantos.registrar.entity.TenantNamespace;
import com.tenantos.registrar.entity.TenantWorkspaceProvisioning;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.enums.AuditEventType;
import com.tenantos.registrar.enums.ProvisioningStep;
import com.tenantos.registrar.repository.AuditLogRepository;
import com.tenantos.registrar.repository.TenantNamespaceRepository;
import com.tenantos.registrar.repository.TenantRepository;
import com.tenantos.registrar.services.aws.EksNamespaceProvisioner;
import com.tenantos.registrar.services.aws.ProvisionedNamespace;
import com.tenantos.registrar.services.tenant.AbstractTransactionalStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@Slf4j
@RequiredArgsConstructor
public class CreateNamespaceStep extends AbstractTransactionalStep {

  private final TenantRepository tenantRepository;
  private final TenantNamespaceRepository tenantNamespaceRepository;
  private final EksNamespaceProvisioner ekaNamespaceProvisioner;
  private final AuditLogRepository auditLogRepository;

  @Override
  public ProvisioningStep step() {
    return ProvisioningStep.CREATE_NAMESPACE;
  }

  @Override
  protected void doExecute(TenantWorkspaceProvisioning job, TenantsRegistration registration) {
    log.info(
        "Creating namespace for tenant {} (provisioning_id {})",
        job.getCompanyEmail(),
        job.getProvisioningId());

    Tenant tenant =
        tenantRepository
            .findById(registration.getTenantId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Tenant "
                            + registration.getTenantId()
                            + " not found - CREATE_TENANT did not run"));

    ProvisionedNamespace actualProvisioned = ekaNamespaceProvisioner.initializeWorkspace(tenant);
    ProvisionedNamespace provisioned =
        new ProvisionedNamespace(
            actualProvisioned.namespace(),
            actualProvisioned.clusterName(),
            actualProvisioned.clusterEndpoint(),
            actualProvisioned.awsRegion());

    // If Kubernetes container is down
    //    ProvisionedNamespace provisioned =
    //        new ProvisionedNamespace(
    //            tenantWorkspaceInitialization.namespaceNameFor(tenant.getSlug()),
    //            eksClusterAuthProvider.clusterName(),
    //            null,
    //            eksClusterAuthProvider.awsRegion());

    // Keyed on tenant_id, so a retry updates this row rather than appending a second one. Loaded
    // before writing rather than rebuilt: saving a freshly built entity merges over the stored row,
    // which would carry the new instance's created_at with it and lose the first apply's timestamp
    // -
    // the very thing that makes created_at worth having next to applied_at.
    TenantNamespace tenantNamespace =
        tenantNamespaceRepository
            .findById(tenant.getId())
            .orElseGet(() -> TenantNamespace.builder().tenantId(tenant.getId()).build());

    tenantNamespace.setNamespace(provisioned.namespace());
    tenantNamespace.setClusterName(provisioned.clusterName());
    tenantNamespace.setClusterEndpoint(provisioned.clusterEndpoint());
    tenantNamespace.setAwsRegion(provisioned.awsRegion());
    tenantNamespace.setAppliedAt(Instant.now());
    tenantNamespaceRepository.save(tenantNamespace);

    auditLogRepository.save(
        AuditLog.builder()
            .tenantId(tenant.getId())
            .actorUserId(requireUserId(registration))
            .eventType(AuditEventType.NAMESPACE_CREATED)
            .resourceType("namespace")
            .resourceId(provisioned.namespace())
            .payload("{}")
            .build());

    // Set on the managed entity rather than through TenantWorkspaceProvisioningStore.advance().
    // That call advanced current_step in a REQUIRES_NEW transaction and this one then advanced it
    // again on commit - two writes to the same row from nested transactions, the outer one flushing
    // a copy loaded before the inner had run. Redundant at best, and it left which write landed
    // depending on flush order. One write, in this transaction, has no such question.
    job.setNamespace(provisioned.namespace());
  }
}
