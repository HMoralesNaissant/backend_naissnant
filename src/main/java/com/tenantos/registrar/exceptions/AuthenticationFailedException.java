package com.tenantos.registrar.exceptions;

/**
 * Every authentication failure, whatever the actual cause. The real reason is recorded in
 * login_audit; the caller gets one indistinguishable message so the endpoint can't be used to
 * discover which addresses are registered.
 */
public class AuthenticationFailedException extends RuntimeException {

  public AuthenticationFailedException(String message) {
    super(message);
  }
}
