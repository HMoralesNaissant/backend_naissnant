package com.tenantos.registrar.exceptions;

/**
 * Credentials were correct, but the background pipeline hasn't produced a tenant membership yet.
 * Distinct from {@link AuthenticationFailedException} because it is not a security failure and the
 * client should retry rather than re-prompt for a password.
 */
public class TenantNotProvisionedException extends RuntimeException {

  public TenantNotProvisionedException(String message) {
    super(message);
  }
}
