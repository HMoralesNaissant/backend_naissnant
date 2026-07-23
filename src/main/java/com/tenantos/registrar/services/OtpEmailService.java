package com.tenantos.registrar.services;

import com.tenantos.registrar.entity.Onboarding;
import com.tenantos.registrar.entity.OnboardingOtp;
import com.tenantos.registrar.repository.OnboardingOtpRepository;
import com.tenantos.registrar.security.HashUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * Generates the OTP code emailed during onboarding verification, persists it (hashed),
 * and sends it. The raw code is never returned to this method's caller - it only ever
 * exists locally, long enough to hash it and build the email body.
 */
@Service
public class OtpEmailService {

    @Value("${fe.onboarding.otp-ttl-seconds:600}")
    private long ttlSeconds;

    @Value("${fe.onboarding.otp-from-address:no-reply@tenantos.local}")
    private String fromAddress;

    private final OnboardingOtpRepository repository;
    private final JavaMailSender mailSender;
    private final SecureRandom secureRandom = new SecureRandom();

    public OtpEmailService(OnboardingOtpRepository repository, JavaMailSender mailSender) {
        this.repository = repository;
        this.mailSender = mailSender;
    }

    @Transactional
    public void generateAndSend(Onboarding onboarding) {
        String code = String.format("%06d", secureRandom.nextInt(1_000_000));

        Instant now = Instant.now();
        OnboardingOtp otp = OnboardingOtp.builder()
                .companyEmail(onboarding.getCompanyEmail())
                .codeHash(HashUtils.sha256Hex(code))
                .expiresAt(now.plusSeconds(ttlSeconds))
                .createdAt(now)
                .build();
        repository.save(otp);

        send(onboarding.getCompanyEmail(), code);
    }

    private void send(String companyEmail, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(companyEmail);
        message.setSubject("Your tenantOs verification code");
        message.setText("Your verification code is: " + code + "\n\nThis code expires in "
                + (ttlSeconds / 60) + " minutes.");
        mailSender.send(message);
    }
}
