package com.tenantos.registrar.domain.response;

/** What a teardown removed; false means there was nothing of that kind to remove. */
public record TenantTeardownResponse(
    String slug,
    boolean namespaceDeleted,
    boolean databaseDropped,
    boolean secretsDeleted,
    boolean recordsDeleted) {}
