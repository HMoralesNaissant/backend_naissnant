package com.tenantos.registrar.services.onboarding;

import com.tenantos.registrar.repository.OnboardingOtpRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Records an OTP validation attempt in its own transaction, committed independently of
 * whatever OnboardingTokenService#validateOtp does afterward. That method throws when
 * the code turns out to be wrong, and if the attempt increment shared that transaction
 * it would get rolled back right along with it - silently defeating the attempts cap,
 * since every wrong guess would erase its own record. REQUIRES_NEW (via this separate
 * bean, so the call actually goes through Spring's transactional proxy instead of a
 * same-class self-invocation that would bypass it) guarantees the increment survives.
 */
@Service
public class OtpAttemptTracker {

    private final OnboardingOtpRepository repository;

    public OtpAttemptTracker(OnboardingOtpRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordAttempt(String companyEmail, Instant now, int maxAttempts) {
        return repository.recordAttempt(companyEmail, now, maxAttempts);
    }
}
