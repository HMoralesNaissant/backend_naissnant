package com.tenantos.registrar.domain.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AccountRegistrationResponse")
public record AccountRegistrationResponse(String companyEmail, String fullName, String accountName, String status) {}
