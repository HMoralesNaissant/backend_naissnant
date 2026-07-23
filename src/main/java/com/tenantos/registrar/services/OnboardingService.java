package com.tenantos.registrar.services;

import com.tenantos.registrar.entity.Onboarding;
import com.tenantos.registrar.repository.OnboardingRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OnboardingService {

    private final OnboardingRepository repository;
    private final OtpEmailService otpEmailService;

    public OnboardingService(OnboardingRepository repository, OtpEmailService otpEmailService) {
        this.repository = repository;
        this.otpEmailService = otpEmailService;
    }

    /**
     * Create a new onboarding record and email its OTP verification code. The onboarding
     * table uses company_email as primary key, so this will throw if a record already
     * exists for the company_email. Sending the OTP email happens inside this same
     * transaction, so a failed send rolls back the record too - otherwise we'd be left
     * with an onboarding row nobody can ever verify.
     */
    @Transactional
    public Onboarding onboardUser(Onboarding onboarding) {
        // Basic validation
        if (onboarding.getCompanyEmail() == null || onboarding.getCompanyEmail().isBlank()) {
            throw new IllegalArgumentException("companyEmail is required");
        }

        if (repository.existsById(onboarding.getCompanyEmail())) {
            throw new DataIntegrityViolationException("An onboarding event already exists for this company_email");
        }

        // status/otpDetails are server-controlled, never trusted from the caller
        onboarding.setStatus("otp-validation");
        onboarding.setOtpDetails("{}");

        Onboarding saved = repository.save(onboarding);
        otpEmailService.generateAndSend(saved);
        return saved;
    }
}
