package com.tenantos.registrar.security;

import com.tenantos.registrar.services.OnboardingOnFlightTokenService;
import com.tenantos.registrar.services.OnboardingOtpService;
import com.tenantos.registrar.services.OnboardingService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates requests carrying a valid onboarding_token cookie with ROLE_ONBOARDING. Paired with
 * SecurityConfig's requestMatchers("/onboarding/**").hasRole("ONBOARDING"), this means the
 * preflight token gates every endpoint under /onboarding declaratively — a new controller method
 * added under that path doesn't need to remember to check the cookie itself. Only a read-only
 * validity check happens here; the atomic single-use consume still happens where the actual write
 * occurs (OnboardingTokenService#validateAndConsume).
 */
public class OnboardingTokenAuthenticationFilter extends OncePerRequestFilter {

  private final OnboardingOnFlightTokenService onboardingOnFlightTokenService;

  public OnboardingTokenAuthenticationFilter(
      OnboardingOnFlightTokenService onboardingOnFlightTokenService) {
    this.onboardingOnFlightTokenService = onboardingOnFlightTokenService;
  }

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {
    String token = onboardingOnFlightTokenService.extractToken(request);
    if (token != null && onboardingOnFlightTokenService.isValid(token)) {
      UsernamePasswordAuthenticationToken auth =
          new UsernamePasswordAuthenticationToken(
              onboardingOnFlightTokenService.getOnboardingSessionTokenCookieName(),
              null,
              List.of(new SimpleGrantedAuthority("ROLE_ONBOARDING")));
      SecurityContextHolder.getContext().setAuthentication(auth);
    }
    filterChain.doFilter(request, response);
  }
}
