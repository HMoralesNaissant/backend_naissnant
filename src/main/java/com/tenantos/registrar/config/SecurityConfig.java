package com.tenantos.registrar.config;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.tenantos.registrar.security.OnboardingTokenAuthenticationFilter;
import com.tenantos.registrar.security.RateLimitFilter;
import com.tenantos.registrar.services.OnboardingOtpService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Security configuration — CSRF is disabled; adds rate limiting and JWT parsing. Public endpoints:
 * GET /onboarding/token, Swagger/OpenAPI docs, and /actuator/**. Everything under /onboarding/**
 * requires ROLE_ONBOARDING (granted by OnboardingTokenAuthenticationFilter from the preflight token
 * cookie — httpOnly, SameSite=Lax — rather than a CSRF token). Everything else requires ROLE_USER
 * (granted by JwtAuthenticationFilter from a Bearer JWT).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(RateLimitProperties.class)
@Slf4j
public class SecurityConfig {

  @Value("${security.cors.allowed-origins}")
  private String[] allowedOrigins;

  @Value("${security.cors.allowed-methods}")
  private String[] allowedMethods;

  @Value("${security.cors.allowed-headers}")
  private String[] allowedHeaders;

  @Value("${security.cors.allow-credentials}")
  private boolean allowCredentials;

  @Value("${security.cors.max-age}")
  private long maxAge;

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      OnboardingOtpService onboardingTokenService,
      RateLimitProperties rateLimitProperties) {
    http.cors(cors -> {})
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth
                    // Token issuance itself must stay public - it's the only /onboarding call a
                    // fresh client can make before it has a token to authenticate with.
                    .requestMatchers(HttpMethod.GET, "/onboarding/token")
                    .permitAll()
                    // Everything else under /onboarding is gated by the preflight token cookie
                    // (OnboardingTokenAuthenticationFilter), not by JWT/ROLE_USER. This covers any
                    // future endpoint added under this path too, not just the current POST
                    // /onboarding.
                    .requestMatchers("/onboarding/**")
                    .hasRole("ONBOARDING")
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers("/actuator/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated());

    for (Map.Entry<String, RateLimitProperties.RateLimiterConfig> entries :
        rateLimitProperties.getConfig().entrySet()) {
      RateLimitProperties.RateLimiterConfig configs = entries.getValue();
      String path = entries.getKey();
      if (!configs.enabled()) {
        continue;
      }
      log.debug("Adding rate limiting for key {}: {}", path, entries.getValue());
      http.addFilterBefore(
          new RateLimitFilter(configs.maxRequests(), configs.windowSeconds(), configs.path()),
          CorsFilter.class);
    }
    http.addFilterBefore(
        new OnboardingTokenAuthenticationFilter(onboardingTokenService), CorsFilter.class);

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
    configuration.setAllowedOrigins(Arrays.asList(this.allowedOrigins));
    configuration.setAllowedMethods(Arrays.asList(this.allowedMethods));
    configuration.setAllowedHeaders(Arrays.asList(this.allowedHeaders));
    configuration.setAllowCredentials(this.allowCredentials);
    configuration.setMaxAge(this.maxAge);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
