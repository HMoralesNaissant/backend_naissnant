package com.tenantos.registrar.services;

import com.tenantos.registrar.domain.response.ValidateOtpResponse;
import com.tenantos.registrar.entity.Onboarding;
import com.tenantos.registrar.entity.OnboardingOtp;
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
import java.util.Optional;

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

  private final OnboardingTokenRepository onboardingTokenRepository;
  private final OnboardingOtpRepository otpRepository;
  private final OnboardingRepository onboardingRepository;
  private final TenantsRegistrationRepository tenantsRegistrationRepository;
  private final OtpAttemptTracker otpAttemptTracker;
  private final OtpEmailService otpEmailService;

  /**
   * Input to validateOtp - the service defines its own command type rather than taking
   * companyEmail/type/code as three positional strings.
   */
  public record OtpValidationCommand(String companyEmail, String type, String code) {}

  /**
   * Validates the OTP code emailed during onboarding (see OtpEmailService). Distinct from the
   * preflight session token above - this checks the 6-digit code the user types in, not the
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
  public ValidateOtpResponse validateOtp(OtpValidationCommand command) {

    Onboarding onboarding =
        onboardingRepository
            .findById(command.companyEmail())
            .orElseThrow(
                () -> new InvalidOtpException("No onboarding record for this company_email"));

    if (!onboarding.getOtpType().equals(command.type())) {
      throw new InvalidOtpException("otpType mismatch for this company_email");
    }
    if (!OnboardingStatus.PENDING.equals(onboarding.getStatus())) {
      throw new InvalidOtpException("Onboarding is not awaiting OTP validation");
    }

    Instant now = Instant.now();
    int attempted = otpAttemptTracker.recordAttempt(command.companyEmail(), now, otpMaxAttempts);
    if (attempted != 1) {
      throw new InvalidOtpException("OTP code is expired or too many attempts have been made");
    }

    OnboardingOtp otp =
        otpRepository
            .findByCompanyEmailAndCreated(command.companyEmail())
            .orElseThrow(
                () -> new InvalidOtpException("No OTP code was issued for this company_email"));

    if (!otp.getCodeHash().equals(hash(command.code()))) {
      throw new InvalidOtpException("Invalid OTP code");
    }

    int validated = otpRepository.markValidated(command.companyEmail(), now);
    if (validated != 1) {
      throw new InvalidOtpException("OTP code was already validated");
    }

    onboarding.setStatus(OnboardingStatus.OTP_VALIDATED);
    onboardingRepository.save(onboarding);
    return new ValidateOtpResponse(otp.getStatus().name(), otp.getOtpId());
  }

  /**
   * Input to resendCode - mirrors OtpValidationCommand, combining the body's companyEmail with the
   * {type} path variable.
   */
  public record ResendCodeCommand(String companyEmail, String type) {}

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
                        "No onboarding record for this company_email, cannot resend code"));

    if (!onboarding.getOtpType().equals(command.type())) {
      throw new InvalidOtpException("otpType mismatch for this company_email");
    }

    otpRepository.markInvalidated(command.companyEmail(), Instant.now());
    otpEmailService.generateAndSend(onboarding);
    return true;
  }

  private static String hash(String rawToken) {
    return HashUtils.sha256Hex(rawToken);
  }
}
