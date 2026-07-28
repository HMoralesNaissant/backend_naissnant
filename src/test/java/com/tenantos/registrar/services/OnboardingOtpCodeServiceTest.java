package com.tenantos.registrar.services;

import com.tenantos.registrar.domain.request.ResendCodeCommand;
import com.tenantos.registrar.entity.Onboarding;
import com.tenantos.registrar.entity.OnboardingOtp;
import com.tenantos.registrar.entity.OnboardingToken;
import com.tenantos.registrar.enums.OnboardingOtpStatus;
import com.tenantos.registrar.enums.OnboardingStatus;
import com.tenantos.registrar.exceptions.InvalidOnboardingTokenException;
import com.tenantos.registrar.exceptions.InvalidOtpException;
import com.tenantos.registrar.repository.OnboardingOtpRepository;
import com.tenantos.registrar.repository.OnboardingRepository;
import com.tenantos.registrar.repository.OnboardingTokenRepository;
import com.tenantos.registrar.repository.TenantsRegistrationRepository;
import com.tenantos.registrar.utils.HashUtils;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnboardingOtpCodeServiceTest {

  private static final String COOKIE_NAME = "onboarding_token";

  @Mock private OnboardingTokenRepository repository;
  @Mock private OnboardingOtpRepository otpRepository;
  @Mock private OnboardingRepository onboardingRepository;
  @Mock private TenantsRegistrationRepository tenantsRegistrationRepository;
  @Mock private OtpAttemptTracker otpAttemptTracker;
  @Mock private OnboardingEmailService onboardingEmailService;
  @Spy private ObjectMapper objectMapper;

  @InjectMocks private OnboardingOnFlightTokenService onboardingOnFlightTokenService;

  @InjectMocks private OnboardingOtpService onboardingOtpService;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(
        onboardingOnFlightTokenService, "onboardingSessionTokenCookieName", COOKIE_NAME);
    ReflectionTestUtils.setField(onboardingOnFlightTokenService, "ttlSeconds", 900L);
    ReflectionTestUtils.setField(onboardingOtpService, "otpMaxAttempts", 5);
  }

  // --- issue ---

  @Test
  void issue_savesToken_andReturnsARawTokenThatHashesToTheSavedHash() {
    String rawToken = onboardingOnFlightTokenService.issue(Map.of("ipAddress", "127.0.0.1"));

    assertThat(rawToken).isNotBlank();

    ArgumentCaptor<OnboardingToken> tokenCaptor = ArgumentCaptor.forClass(OnboardingToken.class);
    verify(repository).save(tokenCaptor.capture());
    assertThat(tokenCaptor.getValue().getTokenHash()).isEqualTo(HashUtils.sha256Hex(rawToken));
    assertThat(tokenCaptor.getValue().getExpiresAt()).isAfter(Instant.now());
    assertThat(tokenCaptor.getValue().getClientDetails()).contains("127.0.0.1");
  }

  // --- validateAndConsume ---

  @Test
  void validateAndConsume_throws_whenTokenIsNull() {
    assertThatThrownBy(() -> onboardingOnFlightTokenService.validateAndConsume(null))
        .isInstanceOf(InvalidOnboardingTokenException.class)
        .hasMessageContaining("Missing onboarding token");
    verify(repository, never()).consume(anyString(), any());
  }

  @Test
  void validateAndConsume_throws_whenTokenIsBlank() {
    assertThatThrownBy(() -> onboardingOnFlightTokenService.validateAndConsume("   "))
        .isInstanceOf(InvalidOnboardingTokenException.class);
  }

  @Test
  void validateAndConsume_throws_whenConsumeAffectsNoRows() {
    when(repository.consume(anyString(), any())).thenReturn(0);

    assertThatThrownBy(() -> onboardingOnFlightTokenService.validateAndConsume("some-token"))
        .isInstanceOf(InvalidOnboardingTokenException.class)
        .hasMessageContaining("invalid, expired, or already used");
  }

  @Test
  void validateAndConsume_succeeds_whenConsumeAffectsOneRow() {
    when(repository.consume(eq(HashUtils.sha256Hex("good-token")), any())).thenReturn(1);

    onboardingOnFlightTokenService.validateAndConsume("good-token");

    verify(repository).consume(eq(HashUtils.sha256Hex("good-token")), any());
  }

  // --- isValid ---

  @Test
  void isValid_returnsFalse_whenTokenIsNullOrBlank() {
    assertThat(onboardingOnFlightTokenService.isValid(null)).isFalse();
    assertThat(onboardingOnFlightTokenService.isValid("  ")).isFalse();
    verify(repository, never()).findById(anyString());
  }

  @Test
  void isValid_returnsFalse_whenTokenNotFound() {
    when(repository.findById(anyString())).thenReturn(Optional.empty());
    assertThat(onboardingOnFlightTokenService.isValid("missing")).isFalse();
  }

  @Test
  void isValid_returnsFalse_whenTokenAlreadyUsed() {
    OnboardingToken used =
        OnboardingToken.builder()
            .tokenHash(HashUtils.sha256Hex("t"))
            .usedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    when(repository.findById(anyString())).thenReturn(Optional.of(used));
    assertThat(onboardingOnFlightTokenService.isValid("t")).isFalse();
  }

  @Test
  void isValid_returnsFalse_whenTokenExpired() {
    OnboardingToken expired =
        OnboardingToken.builder()
            .tokenHash(HashUtils.sha256Hex("t"))
            .expiresAt(Instant.now().minusSeconds(1))
            .build();
    when(repository.findById(anyString())).thenReturn(Optional.of(expired));
    assertThat(onboardingOnFlightTokenService.isValid("t")).isFalse();
  }

  @Test
  void isValid_returnsTrue_whenTokenUnusedAndUnexpired() {
    OnboardingToken live =
        OnboardingToken.builder()
            .tokenHash(HashUtils.sha256Hex("t"))
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    when(repository.findById(anyString())).thenReturn(Optional.of(live));
    assertThat(onboardingOnFlightTokenService.isValid("t")).isTrue();
  }

  // --- extractToken ---

  @Test
  void extractToken_returnsNull_whenNoCookiesPresent() {
    HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
    when(request.getCookies()).thenReturn(null);
    assertThat(onboardingOnFlightTokenService.extractToken(request)).isNull();
  }

  @Test
  void extractToken_returnsNull_whenNoCookieMatchesConfiguredName() {
    HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
    when(request.getCookies()).thenReturn(new Cookie[] {new Cookie("other", "value")});
    assertThat(onboardingOnFlightTokenService.extractToken(request)).isNull();
  }

  @Test
  void extractToken_returnsValue_whenCookieMatchesConfiguredName() {
    HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
    when(request.getCookies()).thenReturn(new Cookie[] {new Cookie(COOKIE_NAME, "raw-value")});
    assertThat(onboardingOnFlightTokenService.extractToken(request)).isEqualTo("raw-value");
  }

  // --- validateOtp ---

  private Onboarding pendingOnboarding() {
    return Onboarding.builder()
        .companyEmail("a@example.com")
        .otpType("code")
        .status(OnboardingStatus.PENDING)
        .build();
  }

  @Test
  void validateOtp_throws_whenOnboardingRecordNotFound() {
    when(onboardingRepository.findById("a@example.com")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                onboardingOtpService.validateOtp(
                    new OnboardingOtpService.OtpValidationCommand(
                        "a@example.com", "code", "123456")))
        .isInstanceOf(InvalidOtpException.class);

    verify(otpAttemptTracker, never()).recordAttempt(anyString(), any(), anyInt());
  }

  @Test
  void validateOtp_throws_whenOtpTypeDoesNotMatchPathType() {
    when(onboardingRepository.findById("a@example.com"))
        .thenReturn(Optional.of(pendingOnboarding()));

    assertThatThrownBy(
            () ->
                onboardingOtpService.validateOtp(
                    new OnboardingOtpService.OtpValidationCommand(
                        "a@example.com", "sms", "123456")))
        .isInstanceOf(InvalidOtpException.class)
        .hasMessageContaining("otpType mismatch");

    verify(otpAttemptTracker, never()).recordAttempt(anyString(), any(), anyInt());
  }

  @Test
  void validateOtp_throws_whenOnboardingNotAwaitingOtpValidation() {
    Onboarding alreadyActive = pendingOnboarding();
    alreadyActive.setStatus(OnboardingStatus.COMPLETED);
    when(onboardingRepository.findById("a@example.com")).thenReturn(Optional.of(alreadyActive));

    assertThatThrownBy(
            () ->
                onboardingOtpService.validateOtp(
                    new OnboardingOtpService.OtpValidationCommand(
                        "a@example.com", "code", "123456")))
        .isInstanceOf(InvalidOtpException.class)
        .hasMessageContaining("not awaiting OTP validation");

    verify(otpAttemptTracker, never()).recordAttempt(anyString(), any(), anyInt());
  }

  @Test
  void validateOtp_throws_whenAttemptTrackerRejects_expiredOrTooManyAttempts() {
    when(onboardingRepository.findById("a@example.com"))
        .thenReturn(Optional.of(pendingOnboarding()));
    when(otpAttemptTracker.recordAttempt(eq("a@example.com"), any(), eq(5))).thenReturn(0);

    assertThatThrownBy(
            () ->
                onboardingOtpService.validateOtp(
                    new OnboardingOtpService.OtpValidationCommand(
                        "a@example.com", "code", "123456")))
        .isInstanceOf(InvalidOtpException.class)
        .hasMessageContaining("expired or too many attempts");

    verify(otpRepository, never()).findById(anyString());
  }

  @Test
  void validateOtp_throws_whenWrongCode_butStillRecordsTheAttempt() {
    // Regression coverage: validateOtp must record the attempt via otpAttemptTracker
    // (its own REQUIRES_NEW transaction) BEFORE throwing on a wrong code. Fixed a bug
    // this session where the whole method was one @Transactional, silently rolling
    // the attempt increment back along with the exception on every wrong guess.
    when(onboardingRepository.findById("a@example.com"))
        .thenReturn(Optional.of(pendingOnboarding()));
    when(otpAttemptTracker.recordAttempt(eq("a@example.com"), any(), eq(5))).thenReturn(1);
    OnboardingOtp otp =
        OnboardingOtp.builder()
            .companyEmail("a@example.com")
            .codeHash(HashUtils.sha256Hex("999999"))
            .build();
    when(otpRepository.findByCompanyEmailAndCreated("a@example.com")).thenReturn(Optional.of(otp));

    assertThatThrownBy(
            () ->
                onboardingOtpService.validateOtp(
                    new OnboardingOtpService.OtpValidationCommand(
                        "a@example.com", "code", "111111")))
        .isInstanceOf(InvalidOtpException.class)
        .hasMessageContaining("Invalid OTP code");

    verify(otpAttemptTracker).recordAttempt(eq("a@example.com"), any(), eq(5));
    verify(otpRepository, never()).markValidated(anyString(), any());
    verify(onboardingRepository, never()).save(any());
  }

  @Test
  void validateOtp_throws_whenNoOtpRowExistsForCompanyEmail() {
    when(onboardingRepository.findById("a@example.com"))
        .thenReturn(Optional.of(pendingOnboarding()));
    when(otpAttemptTracker.recordAttempt(anyString(), any(), anyInt())).thenReturn(1);
    when(otpRepository.findByCompanyEmailAndCreated("a@example.com")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                onboardingOtpService.validateOtp(
                    new OnboardingOtpService.OtpValidationCommand(
                        "a@example.com", "code", "123456")))
        .isInstanceOf(InvalidOtpException.class);
  }

  @Test
  void validateOtp_throws_whenMarkValidatedAffectsNoRows_alreadyValidatedRace() {
    when(onboardingRepository.findById("a@example.com"))
        .thenReturn(Optional.of(pendingOnboarding()));
    when(otpAttemptTracker.recordAttempt(anyString(), any(), anyInt())).thenReturn(1);
    OnboardingOtp otp =
        OnboardingOtp.builder()
            .companyEmail("a@example.com")
            .codeHash(HashUtils.sha256Hex("123456"))
            .build();
    when(otpRepository.findByCompanyEmailAndCreated("a@example.com")).thenReturn(Optional.of(otp));
    when(otpRepository.markValidated(eq("a@example.com"), any())).thenReturn(0);

    assertThatThrownBy(
            () ->
                onboardingOtpService.validateOtp(
                    new OnboardingOtpService.OtpValidationCommand(
                        "a@example.com", "code", "123456")))
        .isInstanceOf(InvalidOtpException.class)
        .hasMessageContaining("already validated");

    verify(onboardingRepository, never()).save(any());
  }

  @Test
  void validateOtp_succeeds_andFlipsOnboardingStatusToOtpValidated() {
    Onboarding onboarding = pendingOnboarding();
    when(onboardingRepository.findById("a@example.com")).thenReturn(Optional.of(onboarding));
    when(otpAttemptTracker.recordAttempt(anyString(), any(), anyInt())).thenReturn(1);
    OnboardingOtp otp =
        OnboardingOtp.builder()
            .companyEmail("a@example.com")
            .codeHash(HashUtils.sha256Hex("123456"))
            .build();
    when(otpRepository.findByCompanyEmailAndCreated("a@example.com")).thenReturn(Optional.of(otp));
    when(otpRepository.markValidated(eq("a@example.com"), any())).thenReturn(1);

    onboardingOtpService.validateOtp(
        new OnboardingOtpService.OtpValidationCommand("a@example.com", "code", "123456"));

    assertThat(onboarding.getStatus()).isEqualTo(OnboardingStatus.OTP_VALIDATED);
    verify(onboardingRepository).save(onboarding);
  }

  // --- validateOtpToken ---

  @Test
  void validateOtpToken_throws_whenOnboardingRecordNotFound() {
    when(onboardingRepository.findById("a@example.com")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> onboardingOtpService.validateOtpToken("a@example.com", "vftk-value"))
        .isInstanceOf(InvalidOtpException.class);

    verify(otpRepository, never()).findByOtpIdAndCompanyEmail(anyString(), anyString());
  }

  @Test
  void validateOtpToken_throws_whenOnboardingNotAwaitingOtpValidation() {
    Onboarding alreadyValidated = pendingOnboarding();
    alreadyValidated.setStatus(OnboardingStatus.OTP_VALIDATED);
    when(onboardingRepository.findById("a@example.com")).thenReturn(Optional.of(alreadyValidated));

    assertThatThrownBy(() -> onboardingOtpService.validateOtpToken("a@example.com", "vftk-value"))
        .isInstanceOf(InvalidOtpException.class)
        .hasMessageContaining("not awaiting OTP validation");
  }

  @Test
  void validateOtpToken_throws_whenTokenDoesNotMatchAnyOtpRow() {
    when(onboardingRepository.findById("a@example.com"))
        .thenReturn(Optional.of(pendingOnboarding()));
    when(otpRepository.findByOtpIdAndCompanyEmail("vftk-value", "a@example.com"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> onboardingOtpService.validateOtpToken("a@example.com", "vftk-value"))
        .isInstanceOf(InvalidOtpException.class)
        .hasMessageContaining("invalid");
  }

  @Test
  void validateOtpToken_throws_whenOtpAlreadyValidated() {
    when(onboardingRepository.findById("a@example.com"))
        .thenReturn(Optional.of(pendingOnboarding()));
    OnboardingOtp otp =
        OnboardingOtp.builder()
            .otpId("vftk-value")
            .companyEmail("a@example.com")
            .status(OnboardingOtpStatus.VALIDATED)
            .expiresAt(Instant.now().plusSeconds(600))
            .build();
    when(otpRepository.findByOtpIdAndCompanyEmail("vftk-value", "a@example.com"))
        .thenReturn(Optional.of(otp));

    assertThatThrownBy(() -> onboardingOtpService.validateOtpToken("a@example.com", "vftk-value"))
        .isInstanceOf(InvalidOtpException.class)
        .hasMessageContaining("already validated");
  }

  @Test
  void validateOtpToken_throws_whenOtpExpired() {
    when(onboardingRepository.findById("a@example.com"))
        .thenReturn(Optional.of(pendingOnboarding()));
    OnboardingOtp otp =
        OnboardingOtp.builder()
            .otpId("vftk-value")
            .companyEmail("a@example.com")
            .status(OnboardingOtpStatus.CREATED)
            .expiresAt(Instant.now().minusSeconds(1))
            .build();
    when(otpRepository.findByOtpIdAndCompanyEmail("vftk-value", "a@example.com"))
        .thenReturn(Optional.of(otp));

    assertThatThrownBy(() -> onboardingOtpService.validateOtpToken("a@example.com", "vftk-value"))
        .isInstanceOf(InvalidOtpException.class)
        .hasMessageContaining("expired");

    verify(otpRepository, never()).markValidated(anyString(), any());
  }

  @Test
  void validateOtpToken_succeeds_andFlipsOnboardingStatusToOtpValidated() {
    Onboarding onboarding = pendingOnboarding();
    when(onboardingRepository.findById("a@example.com")).thenReturn(Optional.of(onboarding));
    OnboardingOtp otp =
        OnboardingOtp.builder()
            .otpId("vftk-value")
            .companyEmail("a@example.com")
            .status(OnboardingOtpStatus.CREATED)
            .expiresAt(Instant.now().plusSeconds(600))
            .build();
    when(otpRepository.findByOtpIdAndCompanyEmail("vftk-value", "a@example.com"))
        .thenReturn(Optional.of(otp));
    when(otpRepository.markValidated(eq("a@example.com"), any())).thenReturn(1);

    onboardingOtpService.validateOtpToken("a@example.com", "vftk-value");

    assertThat(onboarding.getStatus()).isEqualTo(OnboardingStatus.OTP_VALIDATED);
    verify(onboardingRepository).save(onboarding);
  }

  // --- resendCode ---

  @Test
  void resendCode_throwsIllegalArgument_whenCompanyEmailIsNull() {
    assertThatThrownBy(() -> onboardingOtpService.resendCode(new ResendCodeCommand(null, "code")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("companyEmail is required");

    verify(onboardingEmailService, never()).generateAndSend(any());
  }

  @Test
  void resendCode_throwsIllegalArgument_whenCompanyEmailIsBlank() {
    assertThatThrownBy(() -> onboardingOtpService.resendCode(new ResendCodeCommand("   ", "code")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("companyEmail is required");

    verify(onboardingEmailService, never()).generateAndSend(any());
  }

  @Test
  void resendCode_throwsDataIntegrityViolation_whenAlreadyRegistered() {
    when(tenantsRegistrationRepository.existsById("a@example.com")).thenReturn(true);

    assertThatThrownBy(
            () -> onboardingOtpService.resendCode(new ResendCodeCommand("a@example.com", "code")))
        .isInstanceOf(DataIntegrityViolationException.class);

    verify(onboardingRepository, never()).findById(anyString());
    verify(onboardingEmailService, never()).generateAndSend(any());
  }

  @Test
  void resendCode_throwsIllegalArgument_whenNoOnboardingRecordExists() {
    when(tenantsRegistrationRepository.existsById("a@example.com")).thenReturn(false);
    when(onboardingRepository.findById("a@example.com")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> onboardingOtpService.resendCode(new ResendCodeCommand("a@example.com", "code")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("No onboarding record");

    verify(onboardingEmailService, never()).generateAndSend(any());
  }

  @Test
  void resendCode_throwsInvalidOtp_whenTypeDoesNotMatchPathType() {
    when(tenantsRegistrationRepository.existsById("a@example.com")).thenReturn(false);
    when(onboardingRepository.findById("a@example.com"))
        .thenReturn(Optional.of(pendingOnboarding()));

    assertThatThrownBy(
            () -> onboardingOtpService.resendCode(new ResendCodeCommand("a@example.com", "sms")))
        .isInstanceOf(InvalidOtpException.class)
        .hasMessageContaining("otpType mismatch");

    verify(otpRepository, never()).markInvalidated(anyString(), any());
    verify(onboardingEmailService, never()).generateAndSend(any());
  }

  @Test
  void resendCode_invalidatesPreviousCode_andSendsANewOne() {
    Onboarding onboarding = pendingOnboarding();
    when(tenantsRegistrationRepository.existsById("a@example.com")).thenReturn(false);
    when(onboardingRepository.findById("a@example.com")).thenReturn(Optional.of(onboarding));

    boolean result =
        onboardingOtpService.resendCode(new ResendCodeCommand("a@example.com", "code"));

    assertThat(result).isTrue();
    verify(otpRepository).markInvalidated(eq("a@example.com"), any());
    verify(onboardingEmailService).generateAndSend(onboarding);
  }
}
