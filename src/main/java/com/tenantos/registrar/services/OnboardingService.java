package com.tenantos.registrar.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenantos.registrar.entity.Onboarding;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.repository.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

@Service
@RequiredArgsConstructor
public class OnboardingService {

    @Value("${fe.onboarding.session-token-cookie-name:onboarding_token}")
    @Getter
    private String onboardingSessionTokenCookieName;

    @Value("${fe.onboarding.token-ttl-seconds:900}")
    @Getter
    private long ttlSeconds;

    private final TenantsRegistrationRepository repository;
    private final OnboardingRepository onboardingRepository;
    private final PasswordEncoder passwordEncoder;


    private final OtpEmailService otpEmailService;

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

        Onboarding saved = onboardingRepository.save(onboarding);
        otpEmailService.generateAndSend(saved);
        return saved;
    }

    /**
     * Input to register - the service defines its own command type rather than taking
     * companyEmail/fullName/password/accountName as four positional strings.
     */
    public record RegistrationCommand(String companyEmail, String fullName, String password, String accountName) {}

    /**
     * Completes the onboarding funnel: requires the company_email's onboarding record
     * to already be OTP-verified (status "active"), creates the tenant account with a
     * hashed password, and marks the onboarding record "completed".
     */
    @Transactional
    public TenantsRegistration register(RegistrationCommand command) {
        Onboarding onboarding = onboardingRepository.findById(command.companyEmail())
                .orElseThrow(() -> new IllegalArgumentException("No onboarding record for this company_email"));

        if (!"active".equals(onboarding.getStatus())) {
            throw new IllegalStateException("Onboarding is not yet verified for this company_email");
        }
        if (repository.existsById(command.companyEmail())) {
            throw new DataIntegrityViolationException("An account is already registered for this company_email");
        }

        TenantsRegistration saved = repository.save(TenantsRegistration.builder()
                .companyEmail(command.companyEmail())
                .fullName(command.fullName())
                .password(passwordEncoder.encode(command.password()))
                .accountName(command.accountName())
                .build());

        onboarding.setStatus("completed");
        onboardingRepository.save(onboarding);

        return saved;
    }
}
