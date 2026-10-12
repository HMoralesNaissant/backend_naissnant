package com.tenantos.registrar.services.tenant.steps.deploy;

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
import com.tenantos.registrar.repository.TenantsRegistrationRepository;
import com.tenantos.registrar.repository.UserRepository;
import com.tenantos.registrar.entity.User;
import com.tenantos.registrar.services.deploy.TenantAppDeployer;
import com.tenantos.registrar.services.tenant.TenantProvisioningStep;
import com.tenantos.registrar.services.workspace.TenantWorkspaceProvisioningStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Asks CI to deploy the tenant's backend and UI into their namespace.
 *
 * <p>Not transactional: the work is an HTTP call to Forgejo, so this takes the same shape as
 * {@code CreateTenantDatabaseStep} - do the external work, then advance by hand. A crash between
 * the dispatch and the advance re-dispatches on retry, which is harmless: the workflow is an
 * idempotent apply and is serialised per tenant and app.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DispatchTenantDeployStep implements TenantProvisioningStep {

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
    return ProvisioningStep.DISPATCH_TENANT_DEPLOY;
  }

  @Override
  public void execute(TenantWorkspaceProvisioning job) {
    if (!enabled) {
      // Advance rather than throw: an environment with no Forgejo wired up would otherwise
      // dead-letter every tenant here. AWAIT_TENANT_DEPLOY skips on the same flag.
      log.warn(
          "tenant.deployment.enabled is false - skipping app deployment for {}", job.getCompanyEmail());
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
    // The durable record rather than the job's scratch field - the same source TenantSecretWriter
    // uses. Absent means CREATE_NAMESPACE never ran, an ordering bug rather than a retryable one.
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

    log.info(
        "Requesting app deployment into {} for tenant {} (provisioning_id {})",
        namespace,
        tenant.getId(),
        job.getProvisioningId());

    //Deploy tenant app to kubernetes
    tenantAppDeployer.requestDeploy(tenant, namespace);

    auditLogRepository.save(
        AuditLog.builder()
            .tenantId(tenant.getId())
            .actorUserId(
                userRepository.findByEmail(registration.getCompanyEmail()).map(User::getId).orElse(null))
            .eventType(AuditEventType.TENANT_DEPLOY_REQUESTED)
            .resourceType("namespace")
            .resourceId(namespace)
            .payload("{}")
            .build());

    store.advance(job.getProvisioningId(), step().next(), null);
  }
}
