package com.tenantos.registrar.services;

import com.tenantos.registrar.domain.request.OnboardingRegistrationCommand;
import com.tenantos.registrar.entity.Onboarding;
import com.tenantos.registrar.entity.OnboardingOtp;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.enums.OnboardingOtpStatus;
import com.tenantos.registrar.enums.OnboardingStatus;
import com.tenantos.registrar.exceptions.InvalidOtpException;
import com.tenantos.registrar.repository.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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

  private final TenantsRegistrationRepository tenantsRegistrationRepository;
  private final OnboardingRepository onboardingRepository;
  private final OnboardingOtpRepository onboardingOtpRepository;
  private final PasswordEncoder passwordEncoder;
  private final OtpEmailService otpEmailService;

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

    // Invalidate previous otp codes.
    onboardingOtpRepository.markInvalidated(onboarding.getCompanyEmail(), Instant.now());

    //If email is already requested, let's generate a new token and send it
    Optional<Onboarding> prevRegistration =
        onboardingRepository.findById(onboarding.getCompanyEmail());
    if (prevRegistration.isPresent()
        && OnboardingStatus.PENDING.equals(prevRegistration.get().getStatus())) {
      otpEmailService.generateAndSend(prevRegistration.get());
      return prevRegistration.get();
    }

    // status/otpDetails are server-controlled, never trusted from the caller
    onboarding.setStatus(OnboardingStatus.PENDING);
    onboarding.setOtpDetails("{}");

    Onboarding saved = onboardingRepository.save(onboarding);
    otpEmailService.generateAndSend(saved);
    return saved;
  }

  /**
   * Completes the onboarding funnel: requires the company_email's onboarding record to already be
   * OTP-verified (status {@link com.tenantos.registrar.enums.OnboardingStatus#OTP_VALIDATED}),
   * that vrfkToken identifies a validated onboarding_otp row for the same company_email, creates
   * the tenant account with a hashed password, and marks the onboarding record
   * {@link com.tenantos.registrar.enums.OnboardingStatus#COMPLETED}.
   */
  @Transactional
  public TenantsRegistration register(OnboardingRegistrationCommand command) {
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

    TenantsRegistration saved =
        tenantsRegistrationRepository.save(
            TenantsRegistration.builder()
                .companyEmail(command.companyEmail())
                .fullName(command.fullName())
                .password(passwordEncoder.encode(command.password()))
                .accountName(command.accountName())
                .build());

    onboarding.setStatus(OnboardingStatus.COMPLETED);
    onboardingRepository.save(onboarding);

    return saved;
  }
}
