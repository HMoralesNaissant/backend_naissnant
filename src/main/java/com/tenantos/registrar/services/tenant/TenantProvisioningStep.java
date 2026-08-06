package com.tenantos.registrar.services.tenant;

import com.tenantos.registrar.entity.TenantWorkspaceProvisioning;
import com.tenantos.registrar.enums.ProvisioningStep;

/**
 * One stage of tenant provisioning.
 *
 * <p>The contract every implementation must honour: doing the work and advancing
 * {@code current_step} is a single atomic act. For the database-backed steps that falls out of
 * {@link AbstractTransactionalStep} putting both in one transaction. For the steps that touch the
 * outside world it has to be arranged by hand, and the work must be idempotent instead, because a
 * crash between the side effect and the advance will re-run it.
 *
 * <p>That is what lets the retry machinery stay dumb: a failed step leaves {@code current_step}
 * untouched, so the existing backoff/lease path simply re-enters at the same place.
 */
public interface TenantProvisioningStep {

  /** Which step this implementation handles. */
  ProvisioningStep step();

  /**
   * Runs the step and advances the job to the next one. Throwing leaves {@code current_step}
   * exactly as it was, which is how a retry knows where to resume.
   */
  void execute(TenantWorkspaceProvisioning job);
}
