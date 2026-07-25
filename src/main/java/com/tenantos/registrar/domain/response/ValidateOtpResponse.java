package com.tenantos.registrar.domain.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AccountRegistrationResponse")
public record ValidateOtpResponse(String status, String vrfk) {}
