package com.tenantos.registrar.domain.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AccountNameAvailabilityResponse")
public record AccountNameAvailabilityResponse(
    @Schema(
            description =
                "The identifier the account name normalizes to - what the tenant is actually "
                    + "known by (namespace, hostnames). Two names that differ only in case or "
                    + "punctuation share one slug.",
            example = "acme-inc")
        String slug,
    @Schema(description = "true if no existing tenant already uses this slug") boolean available,
    @Schema(
            description = "A free slug to offer instead; null when the name is available",
            example = "acme-inc-2",
            nullable = true)
        String suggestion) {}
