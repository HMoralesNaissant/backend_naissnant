package com.tenantos.registrar.enums;

/**
 * Values for the login_audit table's outcome column. The distinctions here are recorded but never
 * returned to the caller - the login endpoint answers every failure identically so it can't be used
 * to probe which addresses are registered.
 */
public enum LoginOutcome {
  SUCCESS,
  INVALID_CREDENTIALS,
  USER_DISABLED,
  /** Authenticated, but provisioning hasn't produced a tenant membership yet. */
  NO_TENANT,
  /** An already-rotated refresh token was presented - treated as theft. */
  TOKEN_REUSE_DETECTED
}
