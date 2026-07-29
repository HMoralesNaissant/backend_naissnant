package com.tenantos.registrar.domain.request;

/**
 * Input to validateOtp - the service defines its own command type rather than taking
 * companyEmail/type/code as three positional strings.
 */
public record OtpSubmissionCommand(String companyEmail, String type, String code) {}
