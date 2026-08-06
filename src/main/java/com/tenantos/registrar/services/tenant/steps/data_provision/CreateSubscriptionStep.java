package com.tenantos.registrar.services.tenant.steps.data_provision;

import com.tenantos.registrar.entity.AuditLog;
import com.tenantos.registrar.entity.Subscription;
import com.tenantos.registrar.entity.TenantWorkspaceProvisioning;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.enums.AuditEventType;
import com.tenantos.registrar.enums.ProvisioningStep;
import com.tenantos.registrar.enums.SubscriptionStatus;
import com.tenantos.registrar.repository.AuditLogRepository;
import com.tenantos.registrar.repository.SubscriptionRepository;
import com.tenantos.registrar.services.tenant.AbstractTransactionalStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Step 4: open the tenant's free trial.
 *
 * <p>The {@code uq_subscriptions_live} partial index allows only one TRIALING/ACTIVE row per
 * tenant, so this step cannot produce a second trial even if it somehow ran twice - the insert
 * would fail rather than duplicate.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CreateSubscriptionStep extends AbstractTransactionalStep {

  @Value("${tenant.provisioning.default-plan:FREE_TRIAL}")
  private String defaultPlan;

  @Value("${tenant.provisioning.trial-days:14}")
  private int trialDays;

  @Value("${tenant.provisioning.trial-limits:{\"seats\":5,\"apiCallsPerMonth\":100000,\"storageGb\":5}}")
  private String trialLimits;

  private final SubscriptionRepository subscriptionRepository;
  private final AuditLogRepository auditLogRepository;

  @Override
  public ProvisioningStep step() {
    return ProvisioningStep.CREATE_SUBSCRIPTION;
  }

  @Override
  protected void doExecute(TenantWorkspaceProvisioning job, TenantsRegistration registration) {
    log.info("Creating subscription for tenant {}", registration.getTenantId());
    UUID tenantId = requireTenantId(registration);
    Instant now = Instant.now();
    Instant trialEnd = now.plus(trialDays, ChronoUnit.DAYS);

    Subscription subscription =
        subscriptionRepository.save(
            Subscription.builder()
                .tenantId(tenantId)
                .plan(defaultPlan)
                .status(SubscriptionStatus.TRIALING)
                .trialEndsAt(trialEnd)
                .currentPeriodStart(now)
                .currentPeriodEnd(trialEnd)
                .limits(trialLimits)
                .build());

    auditLogRepository.save(
        AuditLog.builder()
            .tenantId(tenantId)
            .actorUserId(requireUserId(registration))
            .eventType(AuditEventType.SUBSCRIPTION_CREATED)
            .resourceType("subscription")
            .resourceId(String.valueOf(subscription.getId()))
            .payload("{\"plan\":\"" + defaultPlan + "\",\"trialDays\":" + trialDays + "}")
            .build());

    log.info("Opened {} trial for tenant {}, ending {}", defaultPlan, tenantId, trialEnd);
  }
}
