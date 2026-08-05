package com.tenantos.registrar.services.workspace;

import com.tenantos.registrar.entity.TenantWorkspaceProvisioning;
import com.tenantos.registrar.enums.TenantsRegistrationStatus;
import com.tenantos.registrar.repository.TenantWorkspaceProvisioningRepository;
import com.tenantos.registrar.repository.TenantsRegistrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The transactional half of workspace provisioning: every short-lived database transaction the
 * provisioning worker needs, each committing on its own so none of them are open while
 * {@link TenantWorkspaceProvisioningService} is out talking to EKS.
 *
 * <p>This is a separate bean on purpose. Spring's {@code @Transactional} is proxy-based, so if
 * these methods lived on the service that calls them, the self-invocation would skip the proxy
 * entirely and they'd silently run with no transaction at all - the claim would never commit and
 * the SELECT ... FOR UPDATE locks would never be taken.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TenantWorkspaceProvisioningStore {

  private static final int MAX_STORED_ERROR_LENGTH = 2000;

  @Value("${workspace.provisioning.batch-size:10}")
  private int batchSize;

  @Value("${workspace.provisioning.backoff-seconds:30}")
  private long backoffSeconds;

  @Value("${workspace.provisioning.max-backoff-seconds:900}")
  private long maxBackoffSeconds;

  @Value("${workspace.provisioning.lease-seconds:300}")
  private long leaseSeconds;

  private final TenantWorkspaceProvisioningRepository provisioningRepository;
  private final TenantsRegistrationRepository tenantsRegistrationRepository;

  /**
   * Marks a batch as ours and commits straight away, so the row locks taken by
   * {@code FOR UPDATE SKIP LOCKED} are released before the slow Kubernetes work begins.
   * REQUIRES_NEW because the callers are an async event listener and a scheduled method, neither
   * of which should end up sharing a transaction with anything else.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<TenantWorkspaceProvisioning> claimBatch() {
    Instant now = Instant.now();
    List<TenantWorkspaceProvisioning> claimable =
        provisioningRepository.findClaimable(now, now.minusSeconds(leaseSeconds), batchSize);

    for (TenantWorkspaceProvisioning job : claimable) {
      job.setStatus(TenantsRegistrationStatus.WORKSPACE_IN_PROGRESS);
      job.setClaimedAt(now);
      tenantsRegistrationRepository.updateWorkspaceStatus(
          job.getCompanyEmail(), TenantsRegistrationStatus.WORKSPACE_IN_PROGRESS);
    }
    return provisioningRepository.saveAll(claimable);
  }

  /**
   * Records success. Returns rows affected (0 or 1); the caller uses a 0 to suppress the
   * confirmation email, so a re-claimed stale lease finishing second can't email the tenant twice.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int markReady(TenantWorkspaceProvisioning job, String namespace) {
    int updated =
        provisioningRepository.markReady(job.getProvisioningId(), namespace, Instant.now());
    if (updated > 0) {
      tenantsRegistrationRepository.updateWorkspaceStatus(
          job.getCompanyEmail(), TenantsRegistrationStatus.WORKSPACE_READY);
    }
    return updated;
  }

  /**
   * Counts the attempt and either schedules a retry with exponential backoff or, once the cap is
   * hit, parks the row as WORKSPACE_FAILED. Failed rows are never deleted - they are the
   * dead-letter record, carrying last_error for diagnosis and manual replay.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(String provisioningId, Exception cause) {
    TenantWorkspaceProvisioning job = provisioningRepository.findById(provisioningId).orElse(null);
    if (job == null) {
      log.error("Provisioning job {} vanished mid-flight", provisioningId, cause);
      return;
    }

    int attempts = job.getAttempts() + 1;
    job.setAttempts(attempts);
    job.setLastError(truncate(String.valueOf(cause)));

    if (attempts >= job.getMaxAttempts()) {
      job.setStatus(TenantsRegistrationStatus.WORKSPACE_FAILED);
      log.error(
          "Workspace provisioning permanently failed for {} after {} attempts",
          job.getCompanyEmail(),
          attempts,
          cause);
    } else {
      job.setStatus(TenantsRegistrationStatus.WORKSPACE_PENDING);
      job.setNextAttemptAt(Instant.now().plusSeconds(backoffFor(attempts)));
      log.warn(
          "Workspace provisioning attempt {}/{} failed for {}, retrying at {}",
          attempts,
          job.getMaxAttempts(),
          job.getCompanyEmail(),
          job.getNextAttemptAt(),
          cause);
    }

    provisioningRepository.save(job);
    tenantsRegistrationRepository.updateWorkspaceStatus(job.getCompanyEmail(), job.getStatus());
  }

  /** Exponential backoff, capped so a long outage doesn't push the final retry days out. */
  private long backoffFor(int attempts) {
    long backoff = backoffSeconds << Math.min(attempts - 1, 16);
    return Math.min(backoff, maxBackoffSeconds);
  }

  private String truncate(String error) {
    return error.length() <= MAX_STORED_ERROR_LENGTH
        ? error
        : error.substring(0, MAX_STORED_ERROR_LENGTH);
  }
}
