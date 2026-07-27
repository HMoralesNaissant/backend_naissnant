package com.tenantos.registrar.domain.request;

/**
 * Input to register - the service defines its own command type rather than taking
 * vrfkToken/companyEmail/fullName/password/accountName as five positional strings.
 */
public record OnboardingRegistrationCommand(
    String vrfkToken, String companyEmail, String fullName, String password, String accountName) {}
