package com.tenantos.registrar.exceptions;

import lombok.Getter;

/**
 * Thrown when POST /onboarding/{type}/validate is called with a code that's wrong, expired, already
 * validated, mismatched against the onboarding record's otpType, or has exceeded the allowed
 * attempt count. Also thrown from POST /onboarding/account-registration when vrfkToken doesn't
 * identify a validated onboarding_otp row for the request's company_email.
 */
public class InvalidOtpException extends RuntimeException {
  @Getter private String UIMessage;

  public InvalidOtpException(String message) {
    super(message);
  }

  public InvalidOtpException(String message, String UIMessage) {
    super(message);
    this.UIMessage = UIMessage;
  }
}
