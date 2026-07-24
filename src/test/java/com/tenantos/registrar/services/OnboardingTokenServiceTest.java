package com.tenantos.registrar.services;

import com.tenantos.registrar.entity.Onboarding;
import com.tenantos.registrar.entity.OnboardingOtp;
import com.tenantos.registrar.entity.OnboardingToken;
import com.tenantos.registrar.exceptions.InvalidOnboardingTokenException;
import com.tenantos.registrar.exceptions.InvalidOtpException;
import com.tenantos.registrar.repository.OnboardingOtpRepository;
import com.tenantos.registrar.repository.OnboardingRepository;
import com.tenantos.registrar.repository.OnboardingTokenRepository;
import com.tenantos.registrar.repository.OnboardingTokenRequestRepository;
import com.tenantos.registrar.security.HashUtils;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
class OnboardingTokenServiceTest {

    private static final String COOKIE_NAME = "onboarding_token";

    @Mock
    private OnboardingTokenRepository repository;
    @Mock
    private OnboardingTokenRequestRepository requestRepository;
    @Mock
    private OnboardingOtpRepository otpRepository;
    @Mock
    private OnboardingRepository onboardingRepository;
    @Mock
    private OtpAttemptTracker otpAttemptTracker;

    @InjectMocks
    private OnboardingTokenService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "onboardingSessionTokenCookieName", COOKIE_NAME);
        ReflectionTestUtils.setField(service, "ttlSeconds", 900L);
        ReflectionTestUtils.setField(service, "otpMaxAttempts", 5);
    }

    // --- issue ---

    @Test
    void issue_savesTokenAndRequestLog_andReturnsARawTokenThatHashesToTheSavedHash() {
        String rawToken = service.issue(Map.of("ipAddress", "127.0.0.1"));

        assertThat(rawToken).isNotBlank();

        ArgumentCaptor<OnboardingToken> tokenCaptor = ArgumentCaptor.forClass(OnboardingToken.class);
        verify(repository).save(tokenCaptor.capture());
        assertThat(tokenCaptor.getValue().getTokenHash()).isEqualTo(HashUtils.sha256Hex(rawToken));
        assertThat(tokenCaptor.getValue().getExpiresAt()).isAfter(Instant.now());

        verify(requestRepository).save(any());
    }

    // --- validateAndConsume ---

    @Test
    void validateAndConsume_throws_whenTokenIsNull() {
        assertThatThrownBy(() -> service.validateAndConsume(null))
                .isInstanceOf(InvalidOnboardingTokenException.class)
                .hasMessageContaining("Missing onboarding token");
        verify(repository, never()).consume(anyString(), any());
    }

    @Test
    void validateAndConsume_throws_whenTokenIsBlank() {
        assertThatThrownBy(() -> service.validateAndConsume("   "))
                .isInstanceOf(InvalidOnboardingTokenException.class);
    }

    @Test
    void validateAndConsume_throws_whenConsumeAffectsNoRows() {
        when(repository.consume(anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.validateAndConsume("some-token"))
                .isInstanceOf(InvalidOnboardingTokenException.class)
                .hasMessageContaining("invalid, expired, or already used");
    }

    @Test
    void validateAndConsume_succeeds_whenConsumeAffectsOneRow() {
        when(repository.consume(eq(HashUtils.sha256Hex("good-token")), any())).thenReturn(1);

        service.validateAndConsume("good-token");

        verify(repository).consume(eq(HashUtils.sha256Hex("good-token")), any());
    }

    // --- isValid ---

    @Test
    void isValid_returnsFalse_whenTokenIsNullOrBlank() {
        assertThat(service.isValid(null)).isFalse();
        assertThat(service.isValid("  ")).isFalse();
        verify(repository, never()).findById(anyString());
    }

    @Test
    void isValid_returnsFalse_whenTokenNotFound() {
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        assertThat(service.isValid("missing")).isFalse();
    }

    @Test
    void isValid_returnsFalse_whenTokenAlreadyUsed() {
        OnboardingToken used = OnboardingToken.builder()
                .tokenHash(HashUtils.sha256Hex("t"))
                .usedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        when(repository.findById(anyString())).thenReturn(Optional.of(used));
        assertThat(service.isValid("t")).isFalse();
    }

    @Test
    void isValid_returnsFalse_whenTokenExpired() {
        OnboardingToken expired = OnboardingToken.builder()
                .tokenHash(HashUtils.sha256Hex("t"))
                .expiresAt(Instant.now().minusSeconds(1))
                .build();
        when(repository.findById(anyString())).thenReturn(Optional.of(expired));
        assertThat(service.isValid("t")).isFalse();
    }

    @Test
    void isValid_returnsTrue_whenTokenUnusedAndUnexpired() {
        OnboardingToken live = OnboardingToken.builder()
                .tokenHash(HashUtils.sha256Hex("t"))
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        when(repository.findById(anyString())).thenReturn(Optional.of(live));
        assertThat(service.isValid("t")).isTrue();
    }

    // --- extractToken ---

    @Test
    void extractToken_returnsNull_whenNoCookiesPresent() {
        HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(null);
        assertThat(service.extractToken(request)).isNull();
    }

    @Test
    void extractToken_returnsNull_whenNoCookieMatchesConfiguredName() {
        HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("other", "value")});
        assertThat(service.extractToken(request)).isNull();
    }

    @Test
    void extractToken_returnsValue_whenCookieMatchesConfiguredName() {
        HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie(COOKIE_NAME, "raw-value")});
        assertThat(service.extractToken(request)).isEqualTo("raw-value");
    }

    // --- validateOtp ---

    private Onboarding pendingOnboarding() {
        return Onboarding.builder()
                .companyEmail("a@example.com")
                .otpType("code")
                .status("otp-validation")
                .build();
    }

    @Test
    void validateOtp_throws_whenOnboardingRecordNotFound() {
        when(onboardingRepository.findById("a@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validateOtp(
                new OnboardingTokenService.OtpValidationCommand("a@example.com", "code", "123456")))
                .isInstanceOf(InvalidOtpException.class);

        verify(otpAttemptTracker, never()).recordAttempt(anyString(), any(), anyInt());
    }

    @Test
    void validateOtp_throws_whenOtpTypeDoesNotMatchPathType() {
        when(onboardingRepository.findById("a@example.com")).thenReturn(Optional.of(pendingOnboarding()));

        assertThatThrownBy(() -> service.validateOtp(
                new OnboardingTokenService.OtpValidationCommand("a@example.com", "sms", "123456")))
                .isInstanceOf(InvalidOtpException.class)
                .hasMessageContaining("otpType mismatch");

        verify(otpAttemptTracker, never()).recordAttempt(anyString(), any(), anyInt());
    }

    @Test
    void validateOtp_throws_whenOnboardingNotAwaitingOtpValidation() {
        Onboarding alreadyActive = pendingOnboarding();
        alreadyActive.setStatus("active");
        when(onboardingRepository.findById("a@example.com")).thenReturn(Optional.of(alreadyActive));

        assertThatThrownBy(() -> service.validateOtp(
                new OnboardingTokenService.OtpValidationCommand("a@example.com", "code", "123456")))
                .isInstanceOf(InvalidOtpException.class)
                .hasMessageContaining("not awaiting OTP validation");

        verify(otpAttemptTracker, never()).recordAttempt(anyString(), any(), anyInt());
    }

    @Test
    void validateOtp_throws_whenAttemptTrackerRejects_expiredOrTooManyAttempts() {
        when(onboardingRepository.findById("a@example.com")).thenReturn(Optional.of(pendingOnboarding()));
        when(otpAttemptTracker.recordAttempt(eq("a@example.com"), any(), eq(5))).thenReturn(0);

        assertThatThrownBy(() -> service.validateOtp(
                new OnboardingTokenService.OtpValidationCommand("a@example.com", "code", "123456")))
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
        when(onboardingRepository.findById("a@example.com")).thenReturn(Optional.of(pendingOnboarding()));
        when(otpAttemptTracker.recordAttempt(eq("a@example.com"), any(), eq(5))).thenReturn(1);
        OnboardingOtp otp = OnboardingOtp.builder()
                .companyEmail("a@example.com")
                .codeHash(HashUtils.sha256Hex("999999"))
                .build();
        when(otpRepository.findById("a@example.com")).thenReturn(Optional.of(otp));

        assertThatThrownBy(() -> service.validateOtp(
                new OnboardingTokenService.OtpValidationCommand("a@example.com", "code", "111111")))
                .isInstanceOf(InvalidOtpException.class)
                .hasMessageContaining("Invalid OTP code");

        verify(otpAttemptTracker).recordAttempt(eq("a@example.com"), any(), eq(5));
        verify(otpRepository, never()).markValidated(anyString(), any());
        verify(onboardingRepository, never()).save(any());
    }

    @Test
    void validateOtp_throws_whenNoOtpRowExistsForCompanyEmail() {
        when(onboardingRepository.findById("a@example.com")).thenReturn(Optional.of(pendingOnboarding()));
        when(otpAttemptTracker.recordAttempt(anyString(), any(), anyInt())).thenReturn(1);
        when(otpRepository.findById("a@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validateOtp(
                new OnboardingTokenService.OtpValidationCommand("a@example.com", "code", "123456")))
                .isInstanceOf(InvalidOtpException.class);
    }

    @Test
    void validateOtp_throws_whenMarkValidatedAffectsNoRows_alreadyValidatedRace() {
        when(onboardingRepository.findById("a@example.com")).thenReturn(Optional.of(pendingOnboarding()));
        when(otpAttemptTracker.recordAttempt(anyString(), any(), anyInt())).thenReturn(1);
        OnboardingOtp otp = OnboardingOtp.builder()
                .companyEmail("a@example.com")
                .codeHash(HashUtils.sha256Hex("123456"))
                .build();
        when(otpRepository.findById("a@example.com")).thenReturn(Optional.of(otp));
        when(otpRepository.markValidated(eq("a@example.com"), any())).thenReturn(0);

        assertThatThrownBy(() -> service.validateOtp(
                new OnboardingTokenService.OtpValidationCommand("a@example.com", "code", "123456")))
                .isInstanceOf(InvalidOtpException.class)
                .hasMessageContaining("already validated");

        verify(onboardingRepository, never()).save(any());
    }

    @Test
    void validateOtp_succeeds_andFlipsOnboardingStatusToActive() {
        Onboarding onboarding = pendingOnboarding();
        when(onboardingRepository.findById("a@example.com")).thenReturn(Optional.of(onboarding));
        when(otpAttemptTracker.recordAttempt(anyString(), any(), anyInt())).thenReturn(1);
        OnboardingOtp otp = OnboardingOtp.builder()
                .companyEmail("a@example.com")
                .codeHash(HashUtils.sha256Hex("123456"))
                .build();
        when(otpRepository.findById("a@example.com")).thenReturn(Optional.of(otp));
        when(otpRepository.markValidated(eq("a@example.com"), any())).thenReturn(1);

        service.validateOtp(new OnboardingTokenService.OtpValidationCommand("a@example.com", "code", "123456"));

        assertThat(onboarding.getStatus()).isEqualTo("active");
        verify(onboardingRepository).save(onboarding);
    }
}
