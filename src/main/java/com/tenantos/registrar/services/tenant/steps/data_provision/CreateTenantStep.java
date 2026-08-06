package com.tenantos.registrar.services.tenant.steps.data_provision;

import com.tenantos.registrar.entity.AuditLog;
import com.tenantos.registrar.entity.Tenant;
import com.tenantos.registrar.entity.TenantSettings;
import com.tenantos.registrar.entity.TenantWorkspaceProvisioning;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.enums.AuditEventType;
import com.tenantos.registrar.enums.ProvisioningStep;
import com.tenantos.registrar.repository.AuditLogRepository;
import com.tenantos.registrar.repository.TenantRepository;
import com.tenantos.registrar.repository.TenantSettingsRepository;
import com.tenantos.registrar.services.tenant.AbstractTransactionalStep;
import com.tenantos.registrar.services.tenant.TenantSlugGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Step 1: create the tenant root and its settings.
 *
 * <p>Also stamps {@code tenants_registration.tenant_id}, which is how every later step finds the
 * tenant it is working on - the job row itself only knows the company email.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CreateTenantStep extends AbstractTransactionalStep {

  @Value("${tenant.provisioning.default-plan:FREE_TRIAL}")
  private String defaultPlan;

  private final TenantRepository tenantRepository;
  private final TenantSettingsRepository tenantSettingsRepository;
  private final TenantSlugGenerator slugGenerator;
  private final AuditLogRepository auditLogRepository;

  @Override
  public ProvisioningStep step() {
    return ProvisioningStep.CREATE_TENANT;
  }

  @Override
  protected void doExecute(TenantWorkspaceProvisioning job, TenantsRegistration registration) {
    log.info("Creating tenant record for  {}  - (provisioning_id {})", registration.getCompanyEmail(), job.getProvisioningId());
    String name =
        registration.getAccountName() == null || registration.getAccountName().isBlank()
            ? registration.getCompanyEmail()
            : registration.getAccountName();

    Tenant tenant =
        tenantRepository.save(
            Tenant.builder()
                .name(name)
                .slug(slugGenerator.generate(registration.getAccountName(), registration.getCompanyEmail()))
                .plan(defaultPlan)
                .build());

    tenantSettingsRepository.save(TenantSettings.builder().tenantId(tenant.getId()).build());

    // The link every subsequent step reads to find its tenant.
    registration.setTenantId(tenant.getId());

    auditLogRepository.save(
        AuditLog.builder()
            .tenantId(tenant.getId())
            .actorUserId(requireUserId(registration))
            .eventType(AuditEventType.TENANT_CREATED)
            .resourceType("tenant")
            .resourceId(String.valueOf(tenant.getId()))
            .payload("{\"slug\":\"" + tenant.getSlug() + "\"}")
            .build());

    log.info("Created tenant {} (slug {}) for {}", tenant.getId(), tenant.getSlug(),
        registration.getCompanyEmail());
  }
}
