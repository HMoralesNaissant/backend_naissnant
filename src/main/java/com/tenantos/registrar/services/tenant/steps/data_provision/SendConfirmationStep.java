package com.tenantos.registrar.services.tenant.steps.data_provision;

import com.tenantos.registrar.entity.AuditLog;
import com.tenantos.registrar.entity.TenantWorkspaceProvisioning;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.entity.User;
import com.tenantos.registrar.enums.AuditEventType;
import com.tenantos.registrar.enums.ProvisioningStep;
import com.tenantos.registrar.repository.AuditLogRepository;
import com.tenantos.registrar.repository.TenantsRegistrationRepository;
import com.tenantos.registrar.repository.UserRepository;
import com.tenantos.registrar.services.onboarding.OnboardingEmailService;
import com.tenantos.registrar.services.tenant.TenantProvisioningStep;
import com.tenantos.registrar.services.workspace.TenantWorkspaceProvisioningStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Step 7: tell the tenant their workspace is ready, and close the audit trail with
 * REGISTRATION_COMPLETED.
 *
 * <p>Not transactional, because {@code sendAccountRegistrationConfirmation} performs SMTP I/O - and
 * because the email itself is a courtesy rather than part of correctness, it swallows its own
 * failures, so this step advances whether or not the message actually left. The alternative would
 * be a tenant whose provisioning is stuck at "not finished" because their mail server was down.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SendConfirmationStep implements TenantProvisioningStep {

  private final TenantsRegistrationRepository tenantsRegistrationRepository;
  private final UserRepository userRepository;
  private final OnboardingEmailService onboardingEmailService;
  private final AuditLogRepository auditLogRepository;
  private final TenantWorkspaceProvisioningStore store;

  @Override
  public ProvisioningStep step() {
    return ProvisioningStep.SEND_CONFIRMATION;
  }

  @Override
  public void execute(TenantWorkspaceProvisioning job) {
    log.info(
        "Sending account registration confirmation for tenant {} - (provisioning_id {})",
        job.getCompanyEmail(),
        job.getProvisioningId());

    TenantsRegistration registration =
        tenantsRegistrationRepository
            .findById(job.getCompanyEmail())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No tenants_registration row for " + job.getCompanyEmail()));
    log.info("Sending account registration confirmation for tenant {}", registration.getTenantId());

    onboardingEmailService.sendAccountRegistrationConfirmation(registration);

    auditLogRepository.save(
        AuditLog.builder()
            .tenantId(registration.getTenantId())
            // Resolved by email: the registration carries no user_id, since users.email is UNIQUE
            // and always equals company_email. Null here only if CREATE_USER somehow never ran,
            // and a missing actor is not worth failing a courtesy notification over.
            .actorUserId(
                userRepository
                    .findByEmail(registration.getCompanyEmail())
                    .map(User::getId)
                    .orElse(null))
            .eventType(AuditEventType.REGISTRATION_COMPLETED)
            .resourceType("tenant")
            .resourceId(String.valueOf(registration.getTenantId()))
            .payload("{\"namespace\":\"" + job.getNamespace() + "\"}")
            .build());

    store.advance(job.getProvisioningId(), step().next(), null);
    log.info("Provisioning complete for tenant {}", registration.getTenantId());
  }
}
