package com.tenantos.registrar.enums;

/**
 * Values for the tenants_registration table's status column, plus the workspace provisioning
 * lifecycle shared by tenants_registration.workspace_status and
 * tenant_workspace_provisioning.status. A CHECK constraint on each column keeps it to its own
 * subset - account state and infra state never mix within a single column.
 */
public enum TenantsRegistrationStatus {
  // tenants_registration.status - the account itself
  ONBOARDING,
  COMPLETED,

  // workspace provisioning lifecycle
  WORKSPACE_PENDING,
  WORKSPACE_IN_PROGRESS,
  WORKSPACE_READY,
  WORKSPACE_FAILED
}
