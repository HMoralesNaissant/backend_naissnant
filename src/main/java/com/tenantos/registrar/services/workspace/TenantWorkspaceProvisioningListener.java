package com.tenantos.registrar.services.workspace;

import com.tenantos.registrar.services.workspace.records.TenantWorkspaceRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Starts provisioning as soon as the registration transaction commits, so a tenant isn't waiting
 * on the next poll tick for work that's already due. The scheduled poller stays the safety net for
 * retries and orphaned jobs; this is purely the happy path.
 *
 * <p>AFTER_COMMIT matters: firing any earlier could hand the worker a job row that a rollback then
 * erases. And this lives in its own bean rather than on the service, because a
 * {@code @TransactionalEventListener} on a self-invoked method would never be seen by the proxy.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "workspace.provisioning.enabled", havingValue = "true")
public class TenantWorkspaceProvisioningListener {

  private final TenantWorkspaceProvisioningService provisioningService;

  @Async("workspaceProvisioningExecutor")
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onTenantWorkspaceRequested(TenantWorkspaceRequestedEvent event) {
    log.debug("Workspace provisioning requested for {}", event.companyEmail());
    try {
      provisioningService.runPendingBatch();
    } catch (Exception e) {
      // Nothing above this can act on the failure - the job row is durable, so the poller will
      // retry it regardless. Log and let it be picked up on the next tick.
      log.warn(
          "Immediate workspace provisioning kick failed for {}; leaving it to the poller",
          event.companyEmail(),
          e);
    }
  }
}
