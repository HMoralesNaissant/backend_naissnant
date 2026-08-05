package com.tenantos.registrar.services;

import com.tenantos.registrar.domain.request.AccountRegistrationRequest;
import com.tenantos.registrar.entity.Onboarding;
import com.tenantos.registrar.entity.OnboardingOtp;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.enums.OnboardingOtpStatus;
import com.tenantos.registrar.enums.OnboardingStatus;
import com.tenantos.registrar.exceptions.InvalidOtpException;
import com.tenantos.registrar.exceptions.OtpGenerationRateLimitedException;
import com.tenantos.registrar.repository.OnboardingOtpRepository;
import com.tenantos.registrar.repository.OnboardingRepository;
import com.tenantos.registrar.repository.TenantsRegistrationRepository;
import com.tenantos.registrar.services.onboarding.OnboardingEmailService;
import com.tenantos.registrar.services.onboarding.OnboardingService;
import com.tenantos.registrar.services.workspace.TenantWorkspaceProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

    @Mock
    private TenantsRegistrationRepository repository;
    @Mock
    private OnboardingRepository onboardingRepository;
    @Mock
    private OnboardingOtpRepository onboardingOtpRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private OnboardingEmailService onboardingEmailService;
    @Mock
    private TenantWorkspaceProvisioningService tenantWorkspaceProvisioningService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private OnboardingService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "otpGenerationMaxCount", 3);
        ReflectionTestUtils.setField(service, "otpGenerationWindowSeconds", 3600L);
    }

    @Test
    void onboardUser_throwsIllegalArgument_whenCompanyEmailIsNull() {
        Onboarding onboarding = Onboarding.builder().companyEmail(null).build();

        assertThatThrownBy(() -> service.onboardUser(onboarding))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("companyEmail is required");

        verify(onboardingEmailService, never()).generateAndSend(any());
    }

    @Test
    void onboardUser_throwsIllegalArgument_whenCompanyEmailIsBlank() {
        Onboarding onboarding = Onboarding.builder().companyEmail("   ").build();

        assertThatThrownBy(() -> service.onboardUser(onboarding))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onboardUser_throwsDataIntegrityViolation_whenCompanyEmailAlreadyExists() {
        Onboarding onboarding = Onboarding.builder().companyEmail("dup@example.com").build();
        when(repository.existsById("dup@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.onboardUser(onboarding))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(repository, never()).save(any());
        verify(onboardingEmailService, never()).generateAndSend(any());
    }

    @Test
    void onboardUser_overwritesClientSuppliedStatusAndOtpDetails_regardlessOfInput() {
        Onboarding onboarding = Onboarding.builder()
                .companyEmail("new@example.com")
                .status(OnboardingStatus.COMPLETED) // attempted tamper
                .otpDetails("{\"hacked\":true}") // attempted tamper
                .build();
        when(repository.existsById("new@example.com")).thenReturn(false);
        when(onboardingRepository.save(any(Onboarding.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Onboarding saved = service.onboardUser(onboarding);

        assertThat(saved.getStatus()).isEqualTo(OnboardingStatus.PENDING);
        assertThat(saved.getOtpDetails()).isEqualTo("{}");
    }

    @Test
    void onboardUser_savesThenTriggersOtpEmail_withTheSavedEntity() {
        Onboarding onboarding = Onboarding.builder().companyEmail("new@example.com").build();
        Onboarding persisted = Onboarding.builder().companyEmail("new@example.com").status(OnboardingStatus.PENDING).build();
        when(repository.existsById("new@example.com")).thenReturn(false);
        when(onboardingRepository.save(any(Onboarding.class))).thenReturn(persisted);

        Onboarding result = service.onboardUser(onboarding);

        assertThat(result).isSameAs(persisted);
        ArgumentCaptor<Onboarding> captor = ArgumentCaptor.forClass(Onboarding.class);
        verify(onboardingEmailService).generateAndSend(captor.capture());
        assertThat(captor.getValue()).isSameAs(persisted);
    }

    @Test
    void onboardUser_throwsRateLimited_whenGenerationCountAtOrAboveMax_forNewRegistration() {
        Onboarding onboarding = Onboarding.builder().companyEmail("new@example.com").build();
        when(repository.existsById("new@example.com")).thenReturn(false);
        when(onboardingOtpRepository.countByCompanyEmailAndCreatedAtAfter(eq("new@example.com"), any()))
                .thenReturn(3L);

        assertThatThrownBy(() -> service.onboardUser(onboarding))
                .isInstanceOf(OtpGenerationRateLimitedException.class);

        verify(onboardingOtpRepository, never()).markInvalidated(anyString(), any());
        verify(onboardingRepository, never()).save(any());
        verify(onboardingEmailService, never()).generateAndSend(any());
    }

    @Test
    void onboardUser_throwsRateLimited_whenGenerationCountAtOrAboveMax_forExistingPendingRegistration() {
        Onboarding onboarding = Onboarding.builder().companyEmail("pending@example.com").build();
        when(repository.existsById("pending@example.com")).thenReturn(false);
        when(onboardingOtpRepository.countByCompanyEmailAndCreatedAtAfter(eq("pending@example.com"), any()))
                .thenReturn(5L);

        assertThatThrownBy(() -> service.onboardUser(onboarding))
                .isInstanceOf(OtpGenerationRateLimitedException.class);

        verify(onboardingOtpRepository, never()).markInvalidated(anyString(), any());
        verify(onboardingRepository, never()).findById(anyString());
        verify(onboardingEmailService, never()).generateAndSend(any());
    }

    @Test
    void register_throwsIllegalArgument_whenNoOnboardingRecordExists() {
        when(onboardingRepository.findById("a@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(command()))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void register_throwsIllegalState_whenOnboardingNotYetVerified() {
        Onboarding onboarding = Onboarding.builder().companyEmail("a@example.com").status(OnboardingStatus.PENDING).build();
        when(onboardingRepository.findById("a@example.com")).thenReturn(Optional.of(onboarding));
        when(onboardingOtpRepository.findByOtpIdAndCompanyEmail("vrfk-token", "a@example.com"))
                .thenReturn(Optional.of(validatedOtp()));

        assertThatThrownBy(() -> service.register(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not yet verified");

        verify(repository, never()).save(any());
    }

    @Test
    void register_throwsDataIntegrityViolation_whenAlreadyRegistered() {
        Onboarding onboarding = Onboarding.builder().companyEmail("a@example.com").status(OnboardingStatus.OTP_VALIDATED).build();
        when(onboardingRepository.findById("a@example.com")).thenReturn(Optional.of(onboarding));
        when(onboardingOtpRepository.findByOtpIdAndCompanyEmail("vrfk-token", "a@example.com"))
                .thenReturn(Optional.of(validatedOtp()));
        when(repository.existsById("a@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(command()))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void register_throwsInvalidOtp_whenVrfkTokenDoesNotMatchAValidatedOtp() {
        Onboarding onboarding = Onboarding.builder().companyEmail("a@example.com").status(OnboardingStatus.PENDING).build();
        when(onboardingRepository.findById("a@example.com")).thenReturn(Optional.of(onboarding));
        when(onboardingOtpRepository.findByOtpIdAndCompanyEmail("vrfk-token", "a@example.com"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(command()))
                .isInstanceOf(InvalidOtpException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void register_throwsInvalidOtp_whenOtpExistsButNotYetValidated() {
        Onboarding onboarding = Onboarding.builder().companyEmail("a@example.com").status(OnboardingStatus.PENDING).build();
        when(onboardingRepository.findById("a@example.com")).thenReturn(Optional.of(onboarding));
        OnboardingOtp otp = OnboardingOtp.builder()
                .otpId("vrfk-token").companyEmail("a@example.com").status(OnboardingOtpStatus.CREATED).build();
        when(onboardingOtpRepository.findByOtpIdAndCompanyEmail("vrfk-token", "a@example.com"))
                .thenReturn(Optional.of(otp));

        assertThatThrownBy(() -> service.register(command()))
                .isInstanceOf(InvalidOtpException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void register_neverPersistsTheRawPassword_andMarksOnboardingCompleted() {
        Onboarding onboarding = Onboarding.builder().companyEmail("a@example.com").status(OnboardingStatus.OTP_VALIDATED).build();
        when(onboardingRepository.findById("a@example.com")).thenReturn(Optional.of(onboarding));
        when(onboardingOtpRepository.findByOtpIdAndCompanyEmail("vrfk-token", "a@example.com"))
                .thenReturn(Optional.of(validatedOtp()));
        when(repository.existsById("a@example.com")).thenReturn(false);
        when(passwordEncoder.encode("raw-password")).thenReturn("bcrypt-hash");
        when(repository.save(any(TenantsRegistration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TenantsRegistration saved = service.register(command());

        assertThat(saved.getPassword()).isEqualTo("bcrypt-hash");
        assertThat(saved.getPassword()).isNotEqualTo("raw-password");
        assertThat(saved.getCompanyEmail()).isEqualTo("a@example.com");
        assertThat(saved.getFullName()).isEqualTo("Full Name");
        assertThat(saved.getAccountName()).isEqualTo("acme");

        assertThat(onboarding.getStatus()).isEqualTo(OnboardingStatus.COMPLETED);
        ArgumentCaptor<Onboarding> onboardingCaptor = ArgumentCaptor.forClass(Onboarding.class);
        verify(onboardingRepository).save(onboardingCaptor.capture());
        assertThat(onboardingCaptor.getValue()).isSameAs(onboarding);

        verify(onboardingEmailService).sendAccountRegistrationInProgress(saved);
    }

    private static AccountRegistrationRequest command() {
    return new AccountRegistrationRequest(
        "vrfk-token", "a@example.com", "Full Name", "raw-password", "acme");
    }

    private static OnboardingOtp validatedOtp() {
        return OnboardingOtp.builder()
                .otpId("vrfk-token").companyEmail("a@example.com").status(OnboardingOtpStatus.VALIDATED).build();
    }

}
