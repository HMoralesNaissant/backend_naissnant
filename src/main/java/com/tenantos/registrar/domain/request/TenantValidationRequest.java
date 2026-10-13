package com.tenantos.registrar.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TenantValidationRequest(@NotBlank @Size(max = 200) String email) {}
