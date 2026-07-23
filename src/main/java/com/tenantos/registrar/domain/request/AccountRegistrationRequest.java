package com.tenantos.registrar.domain.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "AccountRegistrationRequest", description = "Payload to complete tenant account registration")
public record AccountRegistrationRequest(
        @NotBlank(message = "companyEmail is required")
        @Email(message = "companyEmail must be a valid email")
        String companyEmail,
        @NotBlank(message = "fullName is required")
        String fullName,
        @NotBlank(message = "password is required")
        @Size(min = 8, max = 100, message = "password must be at least 8 characters")
        String password,
        @NotBlank(message = "accountName is required")
        String accountName
) {}
