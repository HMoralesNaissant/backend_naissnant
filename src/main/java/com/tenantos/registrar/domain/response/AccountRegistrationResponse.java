package com.tenantos.registrar.domain.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AccountRegistrationResponse")
public record AccountRegistrationResponse(
    String companyEmail,
    String fullName,
    String accountName,
    String status,
    @Schema(
            description =
                "Workspace provisioning state at the time of this response. Always "
                    + "WORKSPACE_PENDING here - the workspace is created in the background.",
            example = "WORKSPACE_PENDING")
        String workspaceStatus,
    @Schema(
            description =
                "Opaque token for GET /onboarding/workspace-status/{provisioningId}. Poll it to "
                    + "follow workspace creation; it is the only credential that endpoint needs.")
        String provisioningId) {}
