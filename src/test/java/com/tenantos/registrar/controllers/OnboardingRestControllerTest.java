package com.tenantos.registrar.controllers;

import com.tenantos.registrar.domain.request.AccountRegistrationRequest;
import com.tenantos.registrar.domain.request.OnboardingRequest;
import com.tenantos.registrar.domain.request.OtpValidationRequest;
import com.tenantos.registrar.domain.response.AccountRegistrationResponse;
import com.tenantos.registrar.entity.Onboarding;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.services.OnboardingService;
import com.tenantos.registrar.services.OnboardingTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnboardingRestControllerTest {

    @Mock
    private OnboardingService onboardingService;
    @Mock
    private OnboardingTokenService onboardingTokenService;

    @InjectMocks
    private OnboardingRestController controller;

    @Test
    void issueToken_setsCookieHeader_andReturnsTtlBody() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("test-agent");
        when(request.getHeader("Accept-Language")).thenReturn("en-US");
        when(request.isSecure()).thenReturn(false);
        when(onboardingTokenService.issue(any())).thenReturn("raw-token-value");
        when(onboardingTokenService.getTtlSeconds()).thenReturn(900L);
        when(onboardingTokenService.getOnboardingSessionTokenCookieName()).thenReturn("onboarding_token");

        ResponseEntity<?> result = controller.issueToken(request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(java.util.Map.of("expiresInSeconds", 900L));

        ArgumentCaptor<String> cookieHeader = ArgumentCaptor.forClass(String.class);
        verify(response).addHeader(eq(HttpHeaders.SET_COOKIE), cookieHeader.capture());
        assertThat(cookieHeader.getValue())
                .contains("onboarding_token=raw-token-value")
                .contains("HttpOnly");
    }

    @Test
    void onboardUser_defaultsOtpTypeToCode_whenNotProvided() {
        OnboardingRequest request = new OnboardingRequest("a@example.com", null);
        Onboarding saved = Onboarding.builder().companyEmail("a@example.com").otpType("code").build();
        when(onboardingService.onboardUser(any(Onboarding.class))).thenReturn(saved);

        ResponseEntity<?> result = controller.onboardUser(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isSameAs(saved);

        ArgumentCaptor<Onboarding> captor = ArgumentCaptor.forClass(Onboarding.class);
        verify(onboardingService).onboardUser(captor.capture());
        assertThat(captor.getValue().getOtpType()).isEqualTo("code");
    }

    @Test
    void onboardUser_passesThroughCallerSuppliedOtpType() {
        OnboardingRequest request = new OnboardingRequest("a@example.com", "sms");
        when(onboardingService.onboardUser(any(Onboarding.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        controller.onboardUser(request);

        ArgumentCaptor<Onboarding> captor = ArgumentCaptor.forClass(Onboarding.class);
        verify(onboardingService).onboardUser(captor.capture());
        assertThat(captor.getValue().getOtpType()).isEqualTo("sms");
    }

    @Test
    void validateOtp_mapsPathVariableAndBodyIntoOneCommand() {
        OtpValidationRequest request = new OtpValidationRequest("a@example.com", "123456");

        ResponseEntity<?> result = controller.validateOtp("code", request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(java.util.Map.of("status", "verified"));
        verify(onboardingTokenService).validateOtp(
                new OnboardingTokenService.OtpValidationCommand("a@example.com", "code", "123456"));
    }

    @Test
    void registerAccount_burnsTokenOnlyAfterRegistrationSucceeds() {
        // Regression coverage: burning the token before calling register() would mean a
        // failed attempt (wrong state, already registered) permanently locks the user out,
        // since a fresh GET /onboarding/token + POST /onboarding can't recreate the
        // already-existing onboarding row. Fixed this session by reordering.
        AccountRegistrationRequest request = new AccountRegistrationRequest(
                "a@example.com", "Full Name", "password123", "acme");
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        TenantsRegistration saved = TenantsRegistration.builder()
                .companyEmail("a@example.com").fullName("Full Name")
                .password("bcrypt-hash").accountName("acme").status("active").build();
        when(onboardingService.register(any())).thenReturn(saved);
        when(onboardingTokenService.extractToken(servletRequest)).thenReturn("raw-token");

        ResponseEntity<?> result = controller.registerAccount(request, servletRequest);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo(new AccountRegistrationResponse(
                "a@example.com", "Full Name", "acme", "active"));

        InOrder order = inOrder(onboardingService, onboardingTokenService);
        order.verify(onboardingService).register(any());
        order.verify(onboardingTokenService).validateAndConsume("raw-token");
    }

    @Test
    void registerAccount_doesNotConsumeToken_whenRegistrationFails() {
        AccountRegistrationRequest request = new AccountRegistrationRequest(
                "a@example.com", "Full Name", "password123", "acme");
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(onboardingService.register(any())).thenThrow(new IllegalStateException("not verified"));

        assertThatThrownBy(() -> controller.registerAccount(request, servletRequest))
                .isInstanceOf(IllegalStateException.class);

        verify(onboardingTokenService, never()).extractToken(servletRequest);
        verify(onboardingTokenService, never()).validateAndConsume(any());
    }
}
