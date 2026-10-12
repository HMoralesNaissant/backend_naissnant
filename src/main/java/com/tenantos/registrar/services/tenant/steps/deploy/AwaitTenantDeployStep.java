package com.tenantos.registrar.services.tenant.steps.deploy;

import com.tenantos.registrar.entity.AuditLog;
import com.tenantos.registrar.entity.Tenant;
import com.tenantos.registrar.entity.TenantNamespace;
import com.tenantos.registrar.entity.TenantWorkspaceProvisioning;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.entity.User;
import com.tenantos.registrar.enums.AuditEventType;
import com.tenantos.registrar.enums.ProvisioningStep;
import com.tenantos.registrar.repository.AuditLogRepository;
import com.tenantos.registrar.repository.TenantNamespaceRepository;
import com.tenantos.registrar.repository.TenantRepository;
import com.tenantos.registrar.repository.TenantsRegistrationRepository;
import com.tenantos.registrar.repository.UserRepository;
import com.tenantos.registrar.services.deploy.TenantAppDeployer;
import com.tenantos.registrar.services.tenant.TenantProvisioningStep;
import com.tenantos.registrar.services.workspace.TenantWorkspaceProvisioningStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Holds the pipeline until the tenant's backend and UI are actually serving, so the confirmation
 * email is not sent for a workspace whose apps are still pulling images.
 *
 * <p>Reads the cluster rather than the CI run: the Deployments are what the tenant will use, and a
 * green workflow does not mean the pods came up. A timeout throws, and the pipeline's backoff
 * re-enters here without re-dispatching.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AwaitTenantDeployStep implements TenantProvisioningStep {

  @Value("${tenant.deployment.enabled:false}")
  private boolean enabled;

  private final TenantsRegistrationRepository tenantsRegistrationRepository;
  private final TenantRepository tenantRepository;
  private final TenantNamespaceRepository tenantNamespaceRepository;
  private final UserRepository userRepository;
  private final AuditLogRepository auditLogRepository;
  private final TenantAppDeployer tenantAppDeployer;
  private final TenantWorkspaceProvisioningStore store;

  @Override
  public ProvisioningStep step() {
    return ProvisioningStep.AWAIT_TENANT_DEPLOY;
  }

  @Override
  public void execute(TenantWorkspaceProvisioning job) {
    if (!enabled) {
      store.advance(job.getProvisioningId(), step().next(), null);
      return;
    }

    TenantsRegistration registration =
        tenantsRegistrationRepository
            .findById(job.getCompanyEmail())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No tenants_registration row for " + job.getCompanyEmail()));
    Tenant tenant =
        tenantRepository
            .findById(registration.getTenantId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Tenant "
                            + registration.getTenantId()
                            + " not found - CREATE_TENANT did not run"));
    String namespace =
        tenantNamespaceRepository
            .findById(tenant.getId())
            .map(TenantNamespace::getNamespace)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No namespace recorded for tenant "
                            + tenant.getId()
                            + " - CREATE_NAMESPACE did not run"));

    log.info("Waiting for tenant apps in {} (provisioning_id {})", namespace, job.getProvisioningId());
    tenantAppDeployer.awaitReady(tenant, namespace);

    auditLogRepository.save(
        AuditLog.builder()
            .tenantId(tenant.getId())
            .actorUserId(
                userRepository.findByEmail(registration.getCompanyEmail()).map(User::getId).orElse(null))
            .eventType(AuditEventType.TENANT_DEPLOY_READY)
            .resourceType("namespace")
            .resourceId(namespace)
            .payload("{}")
            .build());

    store.advance(job.getProvisioningId(), step().next(), null);
    log.info("Tenant apps ready in {}", namespace);
  }
}
