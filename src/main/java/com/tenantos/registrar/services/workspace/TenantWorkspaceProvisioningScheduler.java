package com.tenantos.registrar.services.workspace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The safety net behind {@link TenantWorkspaceProvisioningListener}: picks up jobs whose backoff
 * has elapsed, and jobs left IN_PROGRESS by a worker that died before finishing. Every replica can
 * run this - {@code FOR UPDATE SKIP LOCKED} in the claim query is what keeps them from colliding,
 * so no leader election or distributed lock is needed.
 *
 * <p>fixedDelay rather than fixedRate: a slow batch should push the next poll out, not queue ticks
 * up behind it.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "workspace.provisioning.enabled", havingValue = "true")
public class TenantWorkspaceProvisioningScheduler {

  private final TenantWorkspaceProvisioningService provisioningService;

  @Scheduled(
      fixedDelayString = "${workspace.provisioning.poll-interval-ms:5000}",
      initialDelayString = "${workspace.provisioning.initial-delay-ms:15000}")
  public void pollPendingWorkspaces() {
    try {
      provisioningService.runPendingBatch();
    } catch (Exception e) {
      // Never let an exception escape a @Scheduled method - that would kill the recurring task
      // for the lifetime of the process, silently stranding every job behind it.
      log.error("Workspace provisioning poll failed", e);
    }
  }
}
