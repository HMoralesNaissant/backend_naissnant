package com.tenantos.registrar.services.tenant;

import com.tenantos.registrar.entity.TenantWorkspaceProvisioning;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.entity.User;
import com.tenantos.registrar.repository.TenantWorkspaceProvisioningRepository;
import com.tenantos.registrar.repository.TenantsRegistrationRepository;
import com.tenantos.registrar.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Base for the provisioning steps whose work is purely database writes.
 *
 * <p>Everything a step does, plus the {@code current_step} advance, happens in one transaction
 * opened here. That single decision is the whole idempotency story for these steps: a failure
 * rolls back the work *and* the advance together, so a retry re-enters a step that has left no
 * trace of its previous attempt. No step needs to ask "did I already run?", and no partial rows or
 * duplicate audit events can survive a crash.
 *
 * <p>REQUIRES_NEW because the caller ({@code TenantWorkspaceProvisioningService}) deliberately runs
 * with no transaction open, and each step must commit independently of its neighbours - step 3
 * failing must not undo steps 1 and 2.
 *
 * <p>The job and registration are re-read inside the transaction rather than trusted from the
 * caller: the instance handed over came from the claim, which committed a while ago, and by now a
 * different worker may have taken over the lease.
 */
public abstract class AbstractTransactionalStep implements TenantProvisioningStep {

  @Autowired protected TenantWorkspaceProvisioningRepository provisioningRepository;
  @Autowired protected TenantsRegistrationRepository tenantsRegistrationRepository;
  @Autowired protected UserRepository userRepository;

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void execute(TenantWorkspaceProvisioning detachedJob) {
    TenantWorkspaceProvisioning job =
        provisioningRepository
            .findById(detachedJob.getProvisioningId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Provisioning job disappeared: " + detachedJob.getProvisioningId()));

    TenantsRegistration registration =
        tenantsRegistrationRepository
            .findById(job.getCompanyEmail())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No tenants_registration row for " + job.getCompanyEmail()));

    doExecute(job, registration);

    // Managed entity: dirty checking flushes this with the step's own writes, in one commit.
    job.setCurrentStep(step().next());
  }

  protected abstract void doExecute(
      TenantWorkspaceProvisioning job, TenantsRegistration registration);

  /**
   * The tenant every step after CREATE_TENANT operates on. Failing loudly rather than returning
   * null: a null here would mean the pipeline ran out of order, which is a bug, not a retryable
   * condition - though the retry machinery will treat it as one and eventually dead-letter the job
   * with this message attached, which is the right place to read it.
   */
  protected UUID requireTenantId(TenantsRegistration registration) {
    UUID tenantId = registration.getTenantId();
    if (tenantId == null) {
      throw new IllegalStateException(
          "CREATE_TENANT has not run for " + registration.getCompanyEmail());
    }
    return tenantId;
  }

  /**
   * The user every step after CREATE_USER acts as or on behalf of. Resolved by email rather than a
   * foreign key on the registration: users.email is UNIQUE and is always the registration's
   * company_email, so the column would have duplicated a relationship the schema already
   * guarantees. Absent means CREATE_USER has not run - the same out-of-order bug as above.
   */
  protected User requireUser(TenantsRegistration registration) {
    return userRepository
        .findByEmail(registration.getCompanyEmail())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "CREATE_USER has not run for " + registration.getCompanyEmail()));
  }

  protected UUID requireUserId(TenantsRegistration registration) {
    return requireUser(registration).getId();
  }
}
