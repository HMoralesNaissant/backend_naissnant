package com.tenantos.registrar.services.workspace;

/**
 * Published by {@code OnboardingService.register()} once a tenant's workspace job has been
 * enqueued. Consumed after commit by {@link TenantWorkspaceProvisioningListener}.
 */
public record TenantWorkspaceRequestedEvent(String companyEmail) {}
