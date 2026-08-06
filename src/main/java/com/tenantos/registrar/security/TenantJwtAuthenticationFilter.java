package com.tenantos.registrar.security;

import com.tenantos.registrar.repository.SessionRepository;
import io.jsonwebtoken.Claims;
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
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Authenticates {@code Authorization: Bearer} access tokens issued by {@code TenantAuthService},
 * granting the role authorities the token carries.
 *
 * <p>Also checks that the token's session is still live. That check is the whole reason the
 * sessions table exists: a signed JWT stays cryptographically valid until it expires, so without
 * consulting server-side state, logging out or revoking access would do nothing until the token
 * aged out on its own. It costs one indexed primary-key lookup per request, which is the price of
 * being able to revoke anything at all.
 *
 * <p>Permissive like {@code OnboardingTokenAuthenticationFilter}: an absent or bad token just
 * leaves the context empty and lets the chain decide, rather than rejecting here.
 */
public class TenantJwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtProvider jwtProvider;
  private final SessionRepository sessionRepository;

  public TenantJwtAuthenticationFilter(
      JwtProvider jwtProvider, SessionRepository sessionRepository) {
    this.jwtProvider = jwtProvider;
    this.sessionRepository = sessionRepository;
  }

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {

    String token = extractBearerToken(request);
    if (token != null) {
      Claims claims = jwtProvider.validateToken(token);
      if (claims != null && sessionIsLive(claims)) {
        UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken(
                claims.getSubject(), null, authorities(claims));
        auth.setDetails(claims.get("tenantId", String.class));
        SecurityContextHolder.getContext().setAuthentication(auth);
      }
    }

    filterChain.doFilter(request, response);
  }

  private boolean sessionIsLive(Claims claims) {
    String sessionId = claims.get("sessionId", String.class);
    if (sessionId == null) {
      return false;
    }
    try {
      return sessionRepository
          .findById(UUID.fromString(sessionId))
          .filter(session -> session.isLive(Instant.now()))
          .isPresent();
    } catch (IllegalArgumentException e) {
      return false; // Malformed UUID in a token we signed - treat as unauthenticated.
    }
  }

  /**
   * Role names become ROLE_-prefixed authorities, so {@code hasRole("OWNER")} and
   * {@code @PreAuthorize} work the way Spring Security expects.
   */
  @SuppressWarnings("unchecked")
  private Collection<SimpleGrantedAuthority> authorities(Claims claims) {
    Object roles = claims.get("roles");
    if (!(roles instanceof List<?> roleList)) {
      return List.of();
    }
    return ((List<String>) roleList)
        .stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
  }

  private String extractBearerToken(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      return null;
    }
    String token = header.substring(BEARER_PREFIX.length()).trim();
    return token.isEmpty() ? null : token;
  }
}
