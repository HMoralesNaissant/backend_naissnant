package com.tenantos.registrar.domain.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Public view of a workspace provisioning job. Deliberately omits last_error and attempts - those
 * can carry AWS/EKS internals and retry mechanics that mean nothing to the client.
 */
@Schema(name = "WorkspaceStatusResponse")
public record WorkspaceStatusResponse(
    @Schema(
            description = "Provisioning state",
            allowableValues = {
              "WORKSPACE_PENDING",
              "WORKSPACE_IN_PROGRESS",
              "WORKSPACE_READY",
              "WORKSPACE_FAILED"
            })
        String status,
    @Schema(description = "Kubernetes namespace, once provisioned", example = "tenant-acme")
        String namespace,
    @Schema(description = "When the workspace became ready; null until then") Instant completedAt,
    Instant updatedAt) {}
