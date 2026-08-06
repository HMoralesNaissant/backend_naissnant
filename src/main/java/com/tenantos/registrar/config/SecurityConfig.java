package com.tenantos.registrar.config;

import java.util.Arrays;
import java.util.Map;

import com.tenantos.registrar.repository.SessionRepository;
import com.tenantos.registrar.security.JwtProvider;
import com.tenantos.registrar.security.OnboardingTokenAuthenticationFilter;
import com.tenantos.registrar.security.RateLimitFilter;
import com.tenantos.registrar.security.TenantJwtAuthenticationFilter;
import com.tenantos.registrar.services.onboarding.OnboardingOnFlightTokenService;
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
 * Security configuration — CSRF is disabled; adds rate limiting and two independent authentication
 * mechanisms, one per phase of the tenant lifecycle.
 *
 * <p>Everything under /onboarding/** requires ROLE_ONBOARDING, granted by
 * {@link OnboardingTokenAuthenticationFilter} from the preflight token cookie (httpOnly,
 * SameSite=Lax) rather than a CSRF token. That cookie is consumed by registration, so it does not
 * survive into the authenticated phase.
 *
 * <p>Everything else is gated by {@link TenantJwtAuthenticationFilter}, which validates the bearer
 * access token issued by /tenant-auth/login and grants the tenant roles the token carries.
 *
 * <p>Public endpoints: GET /onboarding/token, GET /onboarding/workspace-status/*, the
 * /tenant-auth/login|refresh|logout trio, Swagger/OpenAPI docs, and /actuator/**.
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

  @Value("${security.jwt.secret:}")
  private String jwtSecret;

  @Value("${security.jwt.ttl-seconds:3600}")
  private long jwtTtlSeconds;

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      OnboardingOnFlightTokenService onboardingOnFlightTokenService,
      RateLimitProperties rateLimitProperties,
      JwtProvider jwtProvider,
      SessionRepository sessionRepository) {
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
                    // Workspace status is polled *after* registration, which deliberately consumes
                    // the preflight token - so by then the client has no /onboarding credential
                    // left. The 64-char random provisioningId in the path is the capability
                    // instead: unguessable, scoped to one tenant, and the response leaks nothing
                    // about whether a given email is registered.
                    .requestMatchers(HttpMethod.GET, "/onboarding/workspace-status/*")
                    .permitAll()
                    // Everything else under /onboarding is gated by the preflight token cookie
                    // (OnboardingTokenAuthenticationFilter), not by JWT/ROLE_USER. This covers any
                    // future endpoint added under this path too, not just the current POST
                    // /onboarding.
                    .requestMatchers("/onboarding/**")
                    .hasRole("ONBOARDING")
                    // The entry points to the authenticated phase, which by definition cannot
                    // require an access token: login establishes one, refresh and logout are
                    // authenticated by the httpOnly refresh cookie instead.
                    .requestMatchers(
                        HttpMethod.POST,
                        "/tenant-auth/login",
                        "/tenant-auth/refresh",
                        "/tenant-auth/logout")
                    .permitAll()
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
        new OnboardingTokenAuthenticationFilter(onboardingOnFlightTokenService), CorsFilter.class);
    http.addFilterBefore(
        new TenantJwtAuthenticationFilter(jwtProvider, sessionRepository), CorsFilter.class);

    return http.build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  /**
   * The signing key for tenant access tokens. The TTL here is only the provider-wide default -
   * access tokens pass their own, much shorter, lifetime per call.
   *
   * <p>With no secret configured, a random one is generated per process. That keeps local
   * development working without ceremony while making the consequence explicit: tokens die at
   * restart and are not valid on another replica. The alternative - shipping a hardcoded default -
   * would be a signing key an attacker could read out of the repository, so it is not offered.
   */
  @Bean
  public JwtProvider jwtProvider() {
    if (jwtSecret == null || jwtSecret.isBlank()) {
      log.warn(
          "JWT_SECRET is not configured - generating an ephemeral signing key. Access tokens will "
              + "be invalidated by a restart and rejected by other replicas. Set JWT_SECRET before "
              + "running more than one instance.");
      byte[] random = new byte[32];
      new java.security.SecureRandom().nextBytes(random);
      return new JwtProvider(
          java.util.Base64.getEncoder().encodeToString(random), jwtTtlSeconds);
    }
    return new JwtProvider(jwtSecret, jwtTtlSeconds);
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
