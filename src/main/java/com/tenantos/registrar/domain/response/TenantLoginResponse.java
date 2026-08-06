package com.tenantos.registrar.domain.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The refresh token is deliberately absent - it goes back in an httpOnly cookie, not the body, so
 * page scripts can't read it.
 */
@Schema(name = "TenantLoginResponse")
public record TenantLoginResponse(
    @Schema(description = "Bearer token for the Authorization header") String accessToken,
    @Schema(description = "Seconds until the access token expires", example = "900")
        long expiresInSeconds,
    String tenantId,
    String tenantSlug,
    String userId,
    List<String> roles) {}
