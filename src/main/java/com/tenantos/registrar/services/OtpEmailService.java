package com.tenantos.registrar.services;

import com.tenantos.registrar.entity.Onboarding;
import com.tenantos.registrar.entity.OnboardingOtp;
import com.tenantos.registrar.repository.OnboardingOtpRepository;
import com.tenantos.registrar.security.HashUtils;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * Generates the OTP code emailed during onboarding verification, persists it (hashed),
 * and sends it. The raw code is never returned to this method's caller - it only ever
 * exists locally, long enough to hash it and render/send the email.
 */
@Service
public class OtpEmailService {

    private static final String TEMPLATE_NAME = "otp-code-template";
    private static final int OTP_ID_LENGTH = 200;
    private static final String OTP_ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    @Value("${fe.onboarding.otp-ttl-seconds:600}")
    private long ttlSeconds;

    @Value("${fe.onboarding.otp-from-address:no-reply@tenantos.local}")
    private String fromAddress;

    @Value("${fe.onboarding.verify-url-template}")
    private String verifyUrlTemplate;

    private final OnboardingOtpRepository repository;
    private final JavaMailSender mailSender;
    private final ITemplateEngine templateEngine;
    private final SecureRandom secureRandom = new SecureRandom();

    public OtpEmailService(OnboardingOtpRepository repository, JavaMailSender mailSender, ITemplateEngine templateEngine) {
        this.repository = repository;
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
    }

    @Transactional
    public void generateAndSend(Onboarding onboarding) {
        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        String otpId = generateUniqueOtpId();
        Instant now = Instant.now();
        OnboardingOtp otp = OnboardingOtp.builder()
                .otpId(otpId)
                .companyEmail(onboarding.getCompanyEmail())
                .codeHash(HashUtils.sha256Hex(code))
                .expiresAt(now.plusSeconds(ttlSeconds))
                .createdAt(now)
                .build();
        OnboardingOtp otpCode = repository.save(otp);

        send(onboarding.getCompanyEmail(), code, otpCode);
    }

    private void send(String companyEmail, String rawCode, OnboardingOtp otp) {
        String verifyUrl = buildVerifyUrl(rawCode, otp);
        String htmlBody = renderHtml(rawCode, verifyUrl);
        String plainTextBody = "Your verification code is: " + rawCode + "\n\n"
                + "Or continue here: " + verifyUrl + "\n\n"
                + "This code expires in " + (ttlSeconds / 60) + " minutes.";

        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(companyEmail);
            helper.setSubject("Your tenantOS verification code");
            helper.setText(plainTextBody, htmlBody);
            mailSender.send(mimeMessage);
        } catch (jakarta.mail.MessagingException e) {
            throw new IllegalStateException("Failed to build the OTP verification email", e);
        }
    }

    private String renderHtml(String rawCode, String verifyUrl) {
        Context context = new Context();
        context.setVariable("codeDisplay", String.join(" ", rawCode.split("")));
        context.setVariable("verifyUrl", verifyUrl);
        return templateEngine.process(TEMPLATE_NAME, context);
    }

    private String buildVerifyUrl(String rawCode, OnboardingOtp otp) {
        String encodedEmail = URLEncoder.encode(otp.getCompanyEmail(), StandardCharsets.UTF_8);
        return verifyUrlTemplate
                .replace("{vftk}", otp.getOtpId())
                .replace("{email}", encodedEmail)
                .replace("{code}", rawCode);
    }

    /**
     * No existsById check against the DB: at OTP_ID_LENGTH=200 chars over a 62-char
     * alphabet, this draws ~1190 bits of entropy from SecureRandom - far beyond a
     * UUIDv4's 122 bits, which is already treated as globally unique without a DB
     * round-trip. Same trust-the-entropy approach as OnboardingTokenService.issue().
     */
    private String generateUniqueOtpId() {
        return randomAlphanumeric(OTP_ID_LENGTH);
    }

    private String randomAlphanumeric(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int idx = secureRandom.nextInt(OTP_ID_ALPHABET.length());
            sb.append(OTP_ID_ALPHABET.charAt(idx));
        }
        return sb.toString();
    }
}
