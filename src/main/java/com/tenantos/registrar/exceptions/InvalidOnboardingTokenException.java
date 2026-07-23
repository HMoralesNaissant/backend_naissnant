package com.tenantos.registrar.exceptions;

/**
 * Thrown when POST /onboarding is called without a valid, unexpired, unused
 * preflight token cookie (see OnboardingTokenService).
 */
public class InvalidOnboardingTokenException extends RuntimeException {
    public InvalidOnboardingTokenException(String message) {
        super(message);
    }
}
