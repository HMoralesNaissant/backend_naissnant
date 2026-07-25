package com.tenantos.registrar.security;

import com.tenantos.registrar.services.OnboardingOtpService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnboardingTokenAuthenticationFilterTest {

    @Mock
    private OnboardingOtpService onboardingTokenService;
    @Mock
    private FilterChain filterChain;

    private OnboardingTokenAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new OnboardingTokenAuthenticationFilter(onboardingTokenService);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private HttpServletRequest request() {
        return mock(HttpServletRequest.class);
    }

    @Test
    void validToken_grantsRoleOnboarding_andContinuesTheChain() throws Exception {
        HttpServletRequest request = request();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(onboardingTokenService.extractToken(request)).thenReturn("raw-token");
        when(onboardingTokenService.isValid("raw-token")).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_ONBOARDING");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void invalidToken_leavesSecurityContextUnauthenticated_butStillContinuesTheChain() throws Exception {
        HttpServletRequest request = request();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(onboardingTokenService.extractToken(request)).thenReturn("stale-token");
        when(onboardingTokenService.isValid("stale-token")).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void missingCookie_neverCallsIsValid_andContinuesUnauthenticated() throws Exception {
        HttpServletRequest request = request();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(onboardingTokenService.extractToken(request)).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(onboardingTokenService, org.mockito.Mockito.never()).isValid(any());
        verify(filterChain).doFilter(request, response);
    }
}
