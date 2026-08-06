package com.tenantos.registrar.controllers;

import com.tenantos.registrar.domain.request.TenantLoginRequest;
import com.tenantos.registrar.domain.response.TenantLoginResponse;
import com.tenantos.registrar.services.auth.TenantAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Optional;

/**
 * Tenant sign-in. Separate from the onboarding funnel: that funnel's preflight cookie is consumed
 * by registration, so it is already gone by the time anyone logs in.
 *
 * <p>The access token comes back in the body for the client to send as a bearer header; the refresh
 * token only ever travels in an httpOnly cookie, so page scripts cannot read it and an XSS bug
 * cannot exfiltrate a month-long credential.
 */
@RestController
@RequestMapping("/tenant-auth")
@RequiredArgsConstructor
public class TenantAuthController {

  @Value("${tenant.auth.refresh-cookie-name:tenant_refresh_token}")
  private String refreshCookieName;

  private final TenantAuthService tenantAuthService;

  @Operation(
      summary = "Sign in to a tenant",
      description =
          "Returns a tenant-scoped access token and sets the refresh token cookie. Responds 409 "
              + "when the credentials are valid but background provisioning has not yet created "
              + "the tenant - the client should retry rather than re-prompt for a password.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Authenticated",
        content = @Content(schema = @Schema(implementation = TenantLoginResponse.class))),
    @ApiResponse(responseCode = "401", description = "Authentication failed", content = @Content),
    @ApiResponse(responseCode = "409", description = "Tenant still provisioning", content = @Content)
  })
  @PostMapping("/login")
  public ResponseEntity<TenantLoginResponse> login(
      @Valid @RequestBody TenantLoginRequest request, HttpServletRequest servletRequest) {
    TenantAuthService.AuthResult result =
        tenantAuthService.login(request, clientContext(servletRequest));
    return respond(result, servletRequest);
  }

  @Operation(
      summary = "Rotate the refresh token",
      description =
          "Exchanges the refresh cookie for a fresh access/refresh pair. Presenting a token that "
              + "has already been rotated is treated as theft: every session for that user is "
              + "revoked and the call fails.")
  @PostMapping("/refresh")
  public ResponseEntity<TenantLoginResponse> refresh(HttpServletRequest servletRequest) {
    TenantAuthService.AuthResult result =
        tenantAuthService.refresh(
            readRefreshCookie(servletRequest).orElse(null), clientContext(servletRequest));
    return respond(result, servletRequest);
  }

  @Operation(
      summary = "Sign out",
      description =
          "Revokes the session and its refresh tokens, and clears the cookie. Always 204 - logging "
              + "out is idempotent and reveals nothing about whether the token was valid.")
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(HttpServletRequest servletRequest) {
    tenantAuthService.logout(readRefreshCookie(servletRequest).orElse(null));

    ResponseCookie cleared =
        ResponseCookie.from(refreshCookieName, "")
            .httpOnly(true)
            .secure(servletRequest.isSecure())
            .sameSite("Lax")
            .path("/tenant-auth")
            .maxAge(0)
            .build();

    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cleared.toString()).build();
  }

  private ResponseEntity<TenantLoginResponse> respond(
      TenantAuthService.AuthResult result, HttpServletRequest servletRequest) {
    // Path-scoped to /tenant-auth: the refresh token is only ever presented to refresh and logout,
    // so there is no reason to attach it to every other request the browser makes.
    ResponseCookie cookie =
        ResponseCookie.from(refreshCookieName, result.rawRefreshToken())
            .httpOnly(true)
            .secure(servletRequest.isSecure())
            .sameSite("Lax")
            .path("/tenant-auth")
            .maxAge(result.refreshTtlSeconds())
            .build();

    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(
            new TenantLoginResponse(
                result.accessToken(),
                result.expiresInSeconds(),
                String.valueOf(result.tenantId()),
                result.tenantSlug(),
                String.valueOf(result.userId()),
                result.roles()));
  }

  private Optional<String> readRefreshCookie(HttpServletRequest request) {
    if (request.getCookies() == null) {
      return Optional.empty();
    }
    return Arrays.stream(request.getCookies())
        .filter(cookie -> refreshCookieName.equals(cookie.getName()))
        .map(Cookie::getValue)
        .filter(value -> value != null && !value.isBlank())
        .findFirst();
  }

  private TenantAuthService.ClientContext clientContext(HttpServletRequest request) {
    return TenantAuthService.ClientContext.of(
        request.getRemoteAddr(), request.getHeader("User-Agent"));
  }
}
