package com.tenantos.registrar.services;

import com.tenantos.registrar.entity.Onboarding;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.repository.OnboardingRepository;
import com.tenantos.registrar.repository.TenantsRegistrationRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantsRegistrationService {

    private final TenantsRegistrationRepository repository;
    private final OnboardingRepository onboardingRepository;
    private final PasswordEncoder passwordEncoder;

    public TenantsRegistrationService(TenantsRegistrationRepository repository,
                                       OnboardingRepository onboardingRepository,
                                       PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.onboardingRepository = onboardingRepository;
        this.passwordEncoder = passwordEncoder;
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
