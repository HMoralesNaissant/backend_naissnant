package com.tenantos.registrar.domain.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "TenantLoginRequest")
public record TenantLoginRequest(
    @NotBlank @Email @Schema(example = "owner@acme.com") String email,
    @NotBlank String password,
    @Schema(
            description =
                "Which tenant to sign in to. Only needed when the user belongs to more than one; "
                    + "omitted, a single membership is selected automatically.",
            example = "acme")
        String tenantSlug) {}
