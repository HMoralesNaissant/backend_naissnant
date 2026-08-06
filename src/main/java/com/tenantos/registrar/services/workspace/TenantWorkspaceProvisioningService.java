package com.tenantos.registrar.services.workspace;

import com.tenantos.registrar.entity.TenantWorkspaceProvisioning;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.enums.ProvisioningStep;
import com.tenantos.registrar.enums.TenantsRegistrationStatus;
import com.tenantos.registrar.repository.TenantWorkspaceProvisioningRepository;
import com.tenantos.registrar.services.tenant.TenantProvisioningStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs tenant provisioning as a durable, resumable pipeline instead of inline with registration.
 *
 * <p>{@link #enqueue} writes only a job row, in the caller's transaction, so the job and the
 * account commit together and neither can exist without the other. {@link #runPendingBatch} then
 * claims due jobs and walks them through the ordered steps in {@link ProvisioningStep}: tenant,
 * RBAC, membership, subscription, API key, Kubernetes namespace, confirmation.
 *
 * <p>Why a pipeline rather than one big method: the steps have wildly different costs and failure
 * modes. Creating a tenant row is a millisecond of local database work; creating an EKS namespace
 * is seconds of network I/O against a service that can be down. Persisting {@code current_step}
 * means an EKS outage retries only the namespace, instead of replaying the RBAC seed and
 * duplicating fifty permission grants each time.
 *
 * <p>This class holds no transaction of its own. Each database step opens and commits its own (see
 * {@code AbstractTransactionalStep}), which is also what keeps a connection from being pinned
 * across the slow external steps.
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
  private final TenantWorkspaceProvisioningStore store;
  private final List<TenantProvisioningStep> stepBeans;
  private final SecureRandom secureRandom = new SecureRandom();

  private Map<ProvisioningStep, TenantProvisioningStep> steps;

  /**
   * Indexes the injected step beans by the stage each handles, and fails fast at startup if the
   * pipeline has a hole in it - far better than discovering a missing step when a tenant's job
   * reaches it at 3am.
   */
  @jakarta.annotation.PostConstruct
  void indexSteps() {
    steps = new EnumMap<>(ProvisioningStep.class);
    for (TenantProvisioningStep bean : stepBeans) {
      TenantProvisioningStep clash = steps.put(bean.step(), bean);
      if (clash != null) {
        throw new IllegalStateException("Two beans handle provisioning step " + bean.step());
      }
    }
    for (ProvisioningStep step : ProvisioningStep.values()) {
      if (step != ProvisioningStep.COMPLETED && !steps.containsKey(step)) {
        throw new IllegalStateException("No bean handles provisioning step " + step);
      }
    }
  }

  /**
   * Records the intent to provision. Deliberately has no transaction of its own so it joins
   * {@code register()}'s - the job row and the tenants_registration row commit or roll back as a
   * unit, which is the whole point of using an outbox rather than firing a thread.
   *
   * <p>Returns the provisioning id, which doubles as the capability token the client polls status
   * with, hence SecureRandom rather than a guessable sequence.
   */
  public String enqueue(TenantsRegistration registration) {
    // registration.workspaceStatus needs no touch here - the entity and column both default to
    // WORKSPACE_PENDING, which is exactly the state this enqueue leaves the tenant in.
    TenantWorkspaceProvisioning job =
        TenantWorkspaceProvisioning.builder()
            .provisioningId(generateProvisioningId())
            .companyEmail(registration.getCompanyEmail())
            .status(TenantsRegistrationStatus.WORKSPACE_PENDING)
            .currentStep(ProvisioningStep.CREATE_USER)
            .maxAttempts(maxAttempts)
            .nextAttemptAt(Instant.now())
            .build();

    provisioningRepository.save(job);
    return job.getProvisioningId();
  }

  /**
   * Claims whatever is due and drives it. Called both by the after-commit kick (so the happy path
   * starts within milliseconds of registration) and by the scheduled poller (so retries and jobs
   * orphaned by a dead worker get picked up). Both entry points are safe to run concurrently on
   * every replica.
   */
  public void runPendingBatch() {
    List<TenantWorkspaceProvisioning> claimed = store.claimBatch();
    if (claimed.isEmpty()) {
      return;
    }

    log.info("Claimed {} tenant provisioning job(s)", claimed.size());
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

  /**
   * Walks a claimed job from wherever it left off to COMPLETED. Each step commits before the next
   * begins, so a throw part-way leaves {@code current_step} at the failed step and the retry
   * resumes precisely there.
   */
  private void provision(TenantWorkspaceProvisioning job) {
    TenantWorkspaceProvisioning current = job;

    while (current.getCurrentStep() != ProvisioningStep.COMPLETED) {
      ProvisioningStep step = current.getCurrentStep();
      log.debug("Running step {} for {}", step, current.getCompanyEmail());

      steps.get(step).execute(current);

      // Re-read rather than trusting an in-memory advance: the step committed in its own
      // transaction, and this instance is detached from it.
      current = store.reload(current.getProvisioningId());

      if (current.getCurrentStep() == step) {
        throw new IllegalStateException(
            "Step " + step + " returned without advancing " + current.getProvisioningId());
      }
    }

    if (store.markReady(current, current.getNamespace()) == 0) {
      log.info(
          "Provisioning for {} was already completed by another worker",
          current.getCompanyEmail());
      return;
    }
    log.info("Tenant provisioning finished for {}", current.getCompanyEmail());
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
