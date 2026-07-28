package com.tenantos.registrar.domain.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "ResendCodeRequest", description = "Payload to request resending the OTP code")
public record ResendCodeCommand(
    @NotBlank(message = "companyEmail is required")
        @Email(message = "companyEmail must be a valid email")
        String companyEmail,
    String type) {}
