package com.tenantos.registrar.enums;

/**
 * Business events written to audit_logs. Stored as the enum name, so renaming a constant rewrites
 * history - add a new one instead.
 */
public enum AuditEventType {
  TENANT_CREATED,
  ROLE_PROVISIONED,
  PERMISSION_PROVISIONED,
  OWNER_ASSIGNED,
  SUBSCRIPTION_CREATED,
  API_KEY_CREATED,
  WORKSPACE_PROVISIONED,
  REGISTRATION_COMPLETED,
  USER_LOGGED_IN,
  USER_LOGGED_OUT,
  REFRESH_TOKEN_ROTATED,
  REFRESH_TOKEN_REUSE_DETECTED,
  NAMESPACE_CREATED
}
