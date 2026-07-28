package com.tenantos.registrar.exceptions;

/**
 * Thrown by OnboardingService#onboardUser when a company_email has already generated
 * otp-generation-max-count OTP codes within the otp-generation-window-seconds rolling
 * window, whether that's the initial signup or the implicit resend for an existing
 * PENDING record.
 */
public class OtpGenerationRateLimitedException extends RuntimeException {
    public OtpGenerationRateLimitedException(String message) {
        super(message);
    }
}
