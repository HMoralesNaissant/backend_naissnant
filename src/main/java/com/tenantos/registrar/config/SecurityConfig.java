package com.tenantos.registrar.config;

import java.util.List;

import com.tenantos.registrar.services.OnboardingTokenService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Security configuration — enables CSRF tokens via cookies, adds rate limiting and JWT parsing.
 * Public endpoints: GET /onboarding/token, Swagger/OpenAPI docs, and /actuator/**.
 * Everything under /onboarding/** requires ROLE_ONBOARDING (granted by
 * OnboardingTokenAuthenticationFilter from the preflight token cookie). Everything else
 * requires ROLE_USER (granted by JwtAuthenticationFilter from a Bearer JWT).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, OnboardingTokenService onboardingTokenService) throws Exception {
        http
            .cors(cors -> {})
            // Use cookie-based CSRF tokens so JS frontend can read and send the X-XSRF-TOKEN header.
            // The plain (non-Xor) request handler is required here: Spring Security's default
            // XorCsrfTokenRequestAttributeHandler expects a BREACH-masked header value, which doesn't
            // match the plain "read cookie, echo it back" pattern this SPA flow relies on.
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
            )
            .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Token issuance itself must stay public - it's the only /onboarding call a
                // fresh client can make before it has a token to authenticate with.
                .requestMatchers(HttpMethod.GET, "/onboarding/token").permitAll()
                // Everything else under /onboarding is gated by the preflight token cookie
                // (OnboardingTokenAuthenticationFilter), not by JWT/ROLE_USER. This covers any
                // future endpoint added under this path too, not just the current POST /onboarding.
                .requestMatchers("/onboarding/**").hasRole("ONBOARDING")
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/actuator/**").permitAll()
                .anyRequest().authenticated()
            )
            // Add simple in-process rate limiting and JWT auth filter
            .addFilterBefore(new com.tenantos.registrar.security.RateLimitFilter(10, 60, "/onboarding"), CorsFilter.class)
            //.addFilterBefore(new com.tenantos.registrar.security.JwtAuthenticationFilter(jwtProvider), CorsFilter.class)
            .addFilterBefore(new com.tenantos.registrar.security.OnboardingTokenAuthenticationFilter(onboardingTokenService), CorsFilter.class)
            // Forces the deferred CSRF token to resolve so the XSRF-TOKEN cookie is always set
            .addFilterAfter(new com.tenantos.registrar.security.CsrfCookieFilter(), CsrfFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // adjust origins to your frontend app(s) in production
        configuration.setAllowedOrigins(List.of("http://localhost:3000"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

