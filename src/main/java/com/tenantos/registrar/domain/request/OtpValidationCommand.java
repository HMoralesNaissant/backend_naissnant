package com.tenantos.registrar.domain.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(name = "OtpValidationRequest", description = "Payload to validate the emailed OTP code")
public record OtpValidationCommand(
        @NotBlank(message = "companyEmail is required")
        @Email(message = "companyEmail must be a valid email")
        String companyEmail,
        @NotBlank(message = "code is required")
        @Pattern(regexp = "\\d{6}", message = "code must be 6 digits")
        String code
) {}
