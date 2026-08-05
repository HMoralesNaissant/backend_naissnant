package com.tenantos.registrar.services.onboarding;

import com.tenantos.registrar.domain.request.OtpSubmissionCommand;
import com.tenantos.registrar.domain.request.ResendCodeCommand;
import com.tenantos.registrar.domain.response.ValidateOtpResponse;
import com.tenantos.registrar.entity.Onboarding;
import com.tenantos.registrar.entity.OnboardingOtp;
import com.tenantos.registrar.enums.OnboardingOtpStatus;
import com.tenantos.registrar.enums.OnboardingStatus;
import com.tenantos.registrar.exceptions.InvalidOtpException;
import com.tenantos.registrar.repository.OnboardingOtpRepository;
import com.tenantos.registrar.repository.OnboardingRepository;
import com.tenantos.registrar.repository.OnboardingTokenRepository;
import com.tenantos.registrar.repository.TenantsRegistrationRepository;
import com.tenantos.registrar.utils.HashUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Issues and consumes short-lived, single-use preflight tokens that gate every endpoint under
 * /onboarding. Kept entirely separate from JwtProvider/JwtAuthenticationFilter: this token only
 * ever grants ROLE_ONBOARDING (see OnboardingTokenAuthenticationFilter), never ROLE_USER or access
 * outside the /onboarding path.
 */
@Service
@RequiredArgsConstructor
public class OnboardingOtpService {

  @Value("${fe.onboarding.otp-max-attempts}")
  private int otpMaxAttempts;

  private final OnboardingOtpRepository otpRepository;
  private final OnboardingRepository onboardingRepository;
  private final TenantsRegistrationRepository tenantsRegistrationRepository;
  private final OtpAttemptTracker otpAttemptTracker;
  private final OnboardingEmailService onboardingEmailService;

  /**
   * Validates the OTP code emailed during onboarding (see OnboardingEmailService). Distinct from
   * the preflight session token above - this checks the 6-digit code the user types in, not the
   * httpOnly cookie. Lives here because the whole /onboarding funnel's "token" concepts are
   * consolidated in this service.
   *
   * <p>The @Transactional on this method covers markValidated/onboarding.save below (its @Modifying
   * query needs an active transaction to run at all). The attempt count is deliberately recorded
   * through otpAttemptTracker instead of directly, since that runs in its own REQUIRES_NEW
   * transaction that commits independently - otherwise a wrong code throwing from within *this*
   * transaction would roll the increment back along with it, silently defeating the attempts cap.
   */
  @Transactional
  public ValidateOtpResponse submitOtp(OtpSubmissionCommand command) {

    Onboarding onboarding =
        onboardingRepository
            .findById(command.companyEmail())
            .orElseThrow(
                () ->
                    new InvalidOtpException(
                        String.format(
                            "Could not find a valid onboarding record for company_email %s",
                            command.companyEmail())));

    if (!onboarding.getOtpType().equals(command.type())) {
      throw new InvalidOtpException(
          String.format("otpType mismatch for this company_email %s", command.companyEmail()));
    }
    if (!OnboardingStatus.PENDING.equals(onboarding.getStatus())) {
      throw new InvalidOtpException(
          String.format(
              "Onboarding is not awaiting OTP validation for company_email %s",
              command.companyEmail()));
    }

    Instant now = Instant.now();
    int attempted = otpAttemptTracker.recordAttempt(command.companyEmail(), now, otpMaxAttempts);
    if (attempted != 1) {
      throw new InvalidOtpException(
          String.format(
              "OTP code is expired or too many attempts have been made for company_email %s",
              command.companyEmail()));
    }

    // There should be only one otp token in the DB with status CREATED
    OnboardingOtp otp =
        otpRepository
            .findByCompanyEmailAndCreated(command.companyEmail())
            .orElseThrow(
                () ->
                    new InvalidOtpException(
                        String.format(
                            "No valid OTP code found for company_email %s",
                            command.companyEmail())));

    if (!otp.getCodeHash().equals(hash(command.code()))) {
      throw new InvalidOtpException(
          String.format(
              "OTP code does not match for this company_email %s", command.companyEmail()),
          "Invalid OTP code provided");
    }

    if (otp.getExpiresAt().isBefore(Instant.now())) {
      throw new InvalidOtpException(
          String.format("OTP code is expired for company_email %s", command.companyEmail()));
    }

    int validated = otpRepository.markValidated(command.companyEmail(), now);
    if (validated != 1) {
      throw new InvalidOtpException(
          String.format(
              "OTP code was already validated for company_email %s", command.companyEmail()));
    }

    onboarding.setStatus(OnboardingStatus.OTP_VALIDATED);
    onboardingRepository.save(onboarding);
    return new ValidateOtpResponse(otp.getStatus().name(), otp.getOtpId());
  }

  /**
   * Validates the OTP via the emailed verify link instead of a typed code: the link carries the OTP
   * row's own id (vrfkToken) as proof, rather than the 6-digit code. Same status checks as
   * validateOtp (onboarding must be PENDING, OTP row must still be CREATED and unexpired), just
   * matched by otpId+companyEmail instead of a code hash - so there's no typed-code attempt cap to
   * enforce here.
   */
  @Transactional
  public void validateOtpToken(String companyEmail, String verificationToken) {
    Onboarding onboarding =
        onboardingRepository
            .findById(companyEmail)
            .orElseThrow(
                () ->
                    new InvalidOtpException(
                        String.format(
                            "No onboarding record for this company_email %s", companyEmail)));

    if (!OnboardingStatus.PENDING.equals(onboarding.getStatus())) {
      throw new InvalidOtpException(
          String.format(
              "Onboarding is not awaiting OTP validation for company_email %s", companyEmail));
    }

    OnboardingOtp otp =
        otpRepository
            .findByCompanyEmailAndCreated(companyEmail)
            .orElseThrow(
                () ->
                    new InvalidOtpException(
                        String.format(
                            "No valid OTP code found for company_email %s", companyEmail)));


    if (otp.getOtpId() == null || !otp.getOtpId().equals(verificationToken)) {
      throw new InvalidOtpException(
          String.format(
              "Verification token does not match for this company_email %s", companyEmail));
    }

    if (otp.getExpiresAt().isBefore(Instant.now())) {
      throw new InvalidOtpException(
          String.format("OTP code is expired for company_email %s", companyEmail));
    }

    int validated = otpRepository.markValidated(companyEmail, Instant.now());
    if (validated != 1) {
      throw new InvalidOtpException(
          String.format("OTP code was already validated for company_email %s", companyEmail));
    }

    onboarding.setStatus(OnboardingStatus.OTP_VALIDATED);
    onboardingRepository.save(onboarding);
  }

  /**
   * Resends the OTP code for an existing, not-yet-registered onboarding record. Invalidates any
   * previously issued code first, same as the initial send in OnboardingService.onboardUser, so a
   * stale code can never be replayed after a resend.
   */
  @Transactional
  public boolean resendCode(ResendCodeCommand command) {
    if (command.companyEmail() == null || command.companyEmail().isBlank()) {
      throw new IllegalArgumentException("companyEmail is required");
    }

    if (tenantsRegistrationRepository.existsById(command.companyEmail())) {
      throw new DataIntegrityViolationException(
          String.format(
              "An onboarding event already processed for this company_email %s",
              command.companyEmail()));
    }

    Onboarding onboarding =
        onboardingRepository
            .findById(command.companyEmail())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format(
                            "No onboarding record for this company_email %s, cannot resend code",
                            command.companyEmail())));

    if (!onboarding.getOtpType().equals(command.type())) {
      throw new InvalidOtpException(
          String.format("otpType mismatch for this company_email %s", command.companyEmail()));
    }

    otpRepository.markInvalidated(command.companyEmail(), Instant.now());
    onboardingEmailService.generateAndSend(onboarding);
    return true;
  }

  private static String hash(String rawToken) {
    return HashUtils.sha256Hex(rawToken);
  }
}
