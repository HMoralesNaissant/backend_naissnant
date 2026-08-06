package com.tenantos.registrar.services.onboarding;

import com.tenantos.registrar.domain.request.AccountRegistrationRequest;
import com.tenantos.registrar.entity.Onboarding;
import com.tenantos.registrar.entity.OnboardingOtp;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.enums.OnboardingOtpStatus;
import com.tenantos.registrar.enums.OnboardingStatus;
import com.tenantos.registrar.enums.TenantsRegistrationStatus;
import com.tenantos.registrar.exceptions.InvalidOtpException;
import com.tenantos.registrar.exceptions.OtpGenerationRateLimitedException;
import com.tenantos.registrar.repository.*;
import com.tenantos.registrar.services.workspace.TenantWorkspaceProvisioningService;
import com.tenantos.registrar.services.workspace.TenantWorkspaceRequestedEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OnboardingService {

  @Value("${fe.onboarding.session-token-cookie-name}")
  @Getter
  private String onboardingSessionTokenCookieName;

  @Value("${fe.onboarding.token-ttl-seconds}")
  @Getter
  private long ttlSeconds;

  @Value("${fe.onboarding.otp-generation-max-count}")
  private int otpGenerationMaxCount;

  @Value("${fe.onboarding.otp-generation-window-seconds}")
  private long otpGenerationWindowSeconds;

  private final TenantsRegistrationRepository tenantsRegistrationRepository;
  private final OnboardingRepository onboardingRepository;
  private final OnboardingOtpRepository onboardingOtpRepository;
  private final PasswordEncoder passwordEncoder;
  private final OnboardingEmailService onboardingEmailService;
  private final TenantWorkspaceProvisioningService tenantWorkspaceProvisioningService;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Create a new onboarding record and email its OTP verification code. The onboarding table uses
   * company_email as primary key, so this will throw if a record already exists for the
   * company_email. Sending the OTP email happens inside this same transaction, so a failed send
   * rolls back the record too - otherwise we'd be left with an onboarding row nobody can ever
   * verify.
   */
  @Transactional
  public Onboarding onboardUser(Onboarding onboarding) {
    // Basic validation
    if (onboarding.getCompanyEmail() == null || onboarding.getCompanyEmail().isBlank()) {
      throw new IllegalArgumentException("companyEmail is required");
    }

    if (tenantsRegistrationRepository.existsById(onboarding.getCompanyEmail())) {
      throw new DataIntegrityViolationException(
          String.format(
              "An onboarding event already processed for this company_email %s",
              onboarding.getCompanyEmail()));
    }

    enforceOtpGenerationRateLimit(onboarding.getCompanyEmail());

    // Invalidate previous otp codes.
    onboardingOtpRepository.markInvalidated(onboarding.getCompanyEmail(), Instant.now());

    Onboarding result;
    // If email is already requested, let's generate a new token and send it
    Optional<Onboarding> prevRegistration =
        onboardingRepository.findById(onboarding.getCompanyEmail());
    if (prevRegistration.isPresent()
        && OnboardingStatus.PENDING.equals(prevRegistration.get().getStatus())) {
      result = prevRegistration.get();
    } else {
      // status/otpDetails are server-controlled, never trusted from the caller
      onboarding.setStatus(OnboardingStatus.PENDING);
      onboarding.setOtpDetails("{}");
      result = onboardingRepository.save(onboarding);
    }
    // Generate and send code if needed
    onboardingEmailService.generateAndSend(result);
    return result;
  }

  /**
   * Caps how many OTP codes a single company_email can generate within a rolling window, counted
   * straight off onboarding_otp.created_at since every generateAndSend call inserts a fresh row
   * there (old ones are marked INVALIDATED, never deleted).
   */
  private void enforceOtpGenerationRateLimit(String companyEmail) {
    Instant windowStart = Instant.now().minusSeconds(otpGenerationWindowSeconds);
    long recentCount =
        onboardingOtpRepository.countByCompanyEmailAndCreatedAtAfter(companyEmail, windowStart);
    if (recentCount >= otpGenerationMaxCount) {
      throw new OtpGenerationRateLimitedException(
          String.format(
              "Too many OTP codes requested for company_email %s, try again later", companyEmail));
    }
  }

  /**
   * Completes the onboarding funnel: requires the company_email's onboarding record to already be
   * OTP-verified (status {@link com.tenantos.registrar.enums.OnboardingStatus#OTP_VALIDATED}), that
   * vrfkToken identifies a validated onboarding_otp row for the same company_email, hashes the
   * password, and marks the onboarding record {@link
   * com.tenantos.registrar.enums.OnboardingStatus#COMPLETED}.
   *
   * <p>Validate and enqueue only: the user and the tenant are both created by the provisioning
   * pipeline - see {@link
   * com.tenantos.registrar.services.workspace.TenantWorkspaceProvisioningService}. The password
   * hash is the one exception, computed here and staged on the registration row, because bcrypt
   * needs a plaintext that exists only for the life of this request.
   */
  @Transactional
  public TenantsRegistration register(AccountRegistrationRequest command) {
    Onboarding onboarding =
        onboardingRepository
            .findById(command.companyEmail())
            .orElseThrow(
                () -> new IllegalArgumentException("No onboarding record for this company_email"));

    OnboardingOtp otp =
        onboardingOtpRepository
            .findByOtpIdAndCompanyEmail(command.vrfkToken(), command.companyEmail())
            .orElseThrow(
                () ->
                    new InvalidOtpException(
                        "Verification token is invalid for this company_email"));
    if (!OnboardingOtpStatus.VALIDATED.equals(otp.getStatus())) {
      throw new InvalidOtpException("Verification token has not been validated");
    }

    if (!OnboardingStatus.OTP_VALIDATED.equals(onboarding.getStatus())) {
      throw new IllegalStateException("Onboarding is not yet verified for this company_email");
    }
    if (tenantsRegistrationRepository.existsById(command.companyEmail())) {
      throw new DataIntegrityViolationException(
          "An account is already registered for this company_email");
    }

    // Hashing happens here, but the user is created by the CREATE_USER provisioning step. Bcrypt
    // needs the plaintext, which exists only for the life of this request, so the hash is staged on
    // the registration row for the step to consume and clear. This is the one part of user creation
    // that cannot be deferred.
    TenantsRegistration saved =
        tenantsRegistrationRepository.save(
            TenantsRegistration.builder()
                .passwordHash(passwordEncoder.encode(command.password()))
                .companyEmail(command.companyEmail())
                .fullName(command.fullName())
                .accountName(command.accountName())
                .status(TenantsRegistrationStatus.COMPLETED)
                .build());

    onboarding.setStatus(OnboardingStatus.COMPLETED);
    onboardingRepository.save(onboarding);

    onboardingEmailService.sendAccountRegistrationInProgress(saved);

    // Everything the account is made of - user, tenant, RBAC, subscription, EKS namespace - is
    // provisioned out-of-band, not here: it ends in seconds of AWS and Kubernetes I/O, which has no
    // business inside this transaction holding a pooled connection open, and an AWS hiccup must not
    // roll back a registration the user is about to be told succeeded. enqueue() only writes the
    // job row, in this same transaction, so the job can neither survive a rollback nor be lost
    // after a commit.
    tenantWorkspaceProvisioningService.enqueue(saved);
    // AFTER_COMMIT listener starts the job immediately; the poller is only the retry safety net.
    eventPublisher.publishEvent(new TenantWorkspaceRequestedEvent(saved.getCompanyEmail()));

    return saved;
  }
}
