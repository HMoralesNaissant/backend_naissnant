package com.tenantos.registrar.services.workspace;

import com.tenantos.registrar.entity.TenantWorkspaceProvisioning;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.enums.TenantsRegistrationStatus;
import com.tenantos.registrar.repository.TenantWorkspaceProvisioningRepository;
import com.tenantos.registrar.repository.TenantsRegistrationRepository;
import com.tenantos.registrar.services.TenantWorkspaceInitialization;
import com.tenantos.registrar.services.onboarding.OnboardingEmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Runs tenant workspace creation as a durable background job instead of inline with registration.
 *
 * <p>Provisioning means an STS presign, an EKS DescribeCluster and a Kubernetes apply - seconds of
 * network I/O that must not sit inside {@code OnboardingService.register()}'s transaction, holding
 * a pooled connection open and rolling an already-confirmed account back on an AWS hiccup. So
 * {@link #enqueue} only writes a job row (in the caller's transaction, so job and account commit
 * together), and {@link #runPendingBatch} executes it later.
 *
 * <p>The slow work here runs with no transaction open at all - every database touch is delegated
 * to {@link TenantWorkspaceProvisioningStore}, which owns the short transactions around it. A
 * worker that dies mid-provision leaves its row IN_PROGRESS, and the lease in
 * {@link TenantWorkspaceProvisioningRepository#findClaimable} hands it to the next poll.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TenantWorkspaceProvisioningService {

  private static final int PROVISIONING_ID_LENGTH = 64;
  private static final String PROVISIONING_ID_ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

  @Value("${workspace.provisioning.max-attempts:5}")
  private int maxAttempts;

  private final TenantWorkspaceProvisioningRepository provisioningRepository;
  private final TenantsRegistrationRepository tenantsRegistrationRepository;
  private final TenantWorkspaceProvisioningStore store;
  private final TenantWorkspaceInitialization tenantWorkspaceInitialization;
  private final OnboardingEmailService onboardingEmailService;
  private final SecureRandom secureRandom = new SecureRandom();

  /**
   * Records the intent to provision. Deliberately has no transaction of its own so it joins
   * {@code register()}'s - the job row and the tenants_registration row commit or roll back as a
   * unit, which is the whole point of using an outbox rather than firing a thread.
   *
   * <p>Returns the provisioning id, which doubles as the capability token the client polls status
   * with, hence SecureRandom rather than a guessable sequence.
   */
  public String enqueue(TenantsRegistration registration) {
    TenantWorkspaceProvisioning job =
        TenantWorkspaceProvisioning.builder()
            .provisioningId(generateProvisioningId())
            .companyEmail(registration.getCompanyEmail())
            .status(TenantsRegistrationStatus.WORKSPACE_PENDING)
            .maxAttempts(maxAttempts)
            .nextAttemptAt(Instant.now())
            .build();

    // registration.workspaceStatus needs no touch here - the entity and column both default to
    // WORKSPACE_PENDING, which is exactly the state this enqueue leaves the tenant in.
    provisioningRepository.save(job);
    return job.getProvisioningId();
  }

  /**
   * Claims whatever is due and provisions it. Called both by the after-commit kick (so the happy
   * path starts within milliseconds of registration) and by the scheduled poller (so retries and
   * jobs orphaned by a dead worker get picked up). Both entry points are safe to run concurrently
   * on every replica.
   */
  public void runPendingBatch() {
    List<TenantWorkspaceProvisioning> claimed = store.claimBatch();
    if (claimed.isEmpty()) {
      return;
    }

    log.info("Claimed {} tenant workspace provisioning job(s)", claimed.size());
    for (TenantWorkspaceProvisioning job : claimed) {
      // One tenant's failure must not abort the rest of the batch.
      try {
        provision(job);
      } catch (Exception e) {
        store.recordFailure(job.getProvisioningId(), e);
      }
    }
  }

  /** Looks up a job by its capability token, for the public status endpoint. */
  public Optional<TenantWorkspaceProvisioning> findByProvisioningId(String provisioningId) {
    return provisioningRepository.findById(provisioningId);
  }

  /** Looks up a tenant's job, so the registration response can hand back its provisioning id. */
  public Optional<TenantWorkspaceProvisioning> findByCompanyEmail(String companyEmail) {
    return provisioningRepository.findByCompanyEmail(companyEmail);
  }

  /** The actual work, running outside any transaction. */
  private void provision(TenantWorkspaceProvisioning job) {
    TenantsRegistration registration =
        tenantsRegistrationRepository
            .findById(job.getCompanyEmail())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No tenants_registration row for company_email " + job.getCompanyEmail()));

    String namespace = tenantWorkspaceInitialization.initializeWorkspace(registration);

    // Email only if this call is the one that actually flipped the row - a stale-lease re-run
    // finishing second sees 0 rows updated and stays quiet rather than emailing the tenant twice.
    if (store.markReady(job, namespace) == 0) {
      log.info(
          "Workspace for {} was already marked ready by another worker; skipping confirmation email",
          job.getCompanyEmail());
      return;
    }

    log.info("Workspace {} ready for {}", namespace, job.getCompanyEmail());
    onboardingEmailService.sendAccountRegistrationConfirmation(registration);
  }

  /**
   * Same trust-the-entropy approach as OnboardingEmailService's OTP ids: 64 random alphanumerics
   * is far past UUIDv4's 122 bits, so no uniqueness round-trip is needed - and unlike an OTP id
   * this doubles as an unguessable capability token for the public status endpoint.
   */
  private String generateProvisioningId() {
    StringBuilder sb = new StringBuilder(PROVISIONING_ID_LENGTH);
    for (int i = 0; i < PROVISIONING_ID_LENGTH; i++) {
      sb.append(
          PROVISIONING_ID_ALPHABET.charAt(secureRandom.nextInt(PROVISIONING_ID_ALPHABET.length())));
    }
    return sb.toString();
  }
}
