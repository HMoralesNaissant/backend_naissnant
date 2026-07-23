package com.tenantos.registrar.domain.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "OnboardingRequest", description = "Payload to create an onboarding event")
public record OnboardingRequest(
        @NotBlank(message = "companyEmail is required")
        @Email(message = "companyEmail must be a valid email")
        @Schema(description = "Company email (primary identifier)", example = "contact@example.com")
        String companyEmail,
        @Schema(description = "OTP type", example = "code")
        String otpType
) {}
