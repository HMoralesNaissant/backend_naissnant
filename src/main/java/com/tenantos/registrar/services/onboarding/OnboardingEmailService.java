package com.tenantos.registrar.services.onboarding;

import com.tenantos.registrar.entity.Onboarding;
import com.tenantos.registrar.entity.OnboardingOtp;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.repository.OnboardingOtpRepository;
import com.tenantos.registrar.utils.HashUtils;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
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
 * Every email sent during the onboarding funnel: the OTP verification code and the
 * post-registration notice. Kept in one class since both are thin
 * render-a-Thymeleaf-template-and-send-via-JavaMailSender operations over the same
 * mail configuration - splitting them added no isolation, just duplication.
 *
 * <p>The two sends have different failure semantics. generateAndSend's send failure
 * propagates and rolls back its caller's transaction: without the email nobody can ever
 * verify the onboarding row, so it may as well not exist. sendAccountRegistrationInProgress
 * swallows failures instead - by the time it runs the account already exists and is fully
 * usable, so it's a courtesy notification, not part of the funnel's correctness.
 */
@Slf4j
@Service
public class OnboardingEmailService {

    private static final String OTP_TEMPLATE_NAME = "otp-code-template";
    private static final String ACCOUNT_REGISTRATION_INPROGRESS_TEMPLATE_NAME = "account-registration-inprogress";
    private static final String ACCOUNT_REGISTRATION_CONFIRMATION_TEMPLATE_NAME = "account-registration-confirmation";
    private static final int OTP_ID_LENGTH = 200;
    private static final String OTP_ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    @Value("${fe.onboarding.otp-ttl-seconds:600}")
    private long ttlSeconds;

    @Value("${fe.onboarding.otp-from-address:no-reply@tenantos.local}")
    private String fromAddress;

    @Value("${fe.onboarding.verify-url-template}")
    private String verifyUrlTemplate;

    @Value("${fe.onboarding.login-url}")
    private String loginUrl;

    private final OnboardingOtpRepository repository;
    private final JavaMailSender mailSender;
    private final ITemplateEngine templateEngine;
    private final SecureRandom secureRandom = new SecureRandom();

    public OnboardingEmailService(OnboardingOtpRepository repository, JavaMailSender mailSender, ITemplateEngine templateEngine) {
        this.repository = repository;
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
    }

    // --- OTP verification email ---

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

        sendOtpEmail(onboarding.getCompanyEmail(), code, otpCode);
    }

    private void sendOtpEmail(String companyEmail, String rawCode, OnboardingOtp otp) {
        String verifyUrl = buildVerifyUrl(otp);
        String htmlBody = renderOtpHtml(rawCode, verifyUrl);
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

    private String renderOtpHtml(String rawCode, String verifyUrl) {
        Context context = new Context();
        context.setVariable("codeDisplay", String.join(" ", rawCode.split("")));
        context.setVariable("verifyUrl", verifyUrl);
        return templateEngine.process(OTP_TEMPLATE_NAME, context);
    }

    private String buildVerifyUrl(OnboardingOtp otp) {
        String encodedEmail = URLEncoder.encode(otp.getCompanyEmail(), StandardCharsets.UTF_8);
        return verifyUrlTemplate
                .replace("{vrfk}", otp.getOtpId())
                .replace("{email}", encodedEmail);
    }

    /**
     * No existsById check against the DB: at OTP_ID_LENGTH=200 chars over a 62-char
     * alphabet, this draws ~1190 bits of entropy from SecureRandom - far beyond a
     * UUIDv4's 122 bits, which is already treated as globally unique without a DB
     * round-trip. Same trust-the-entropy approach as OnboardingTokenService.issue().
     */
    private String generateUniqueOtpId() {
        return randomAlphanumeric();
    }

    private String randomAlphanumeric() {
        StringBuilder sb = new StringBuilder(OTP_ID_LENGTH);
        for (int i = 0; i < OTP_ID_LENGTH; i++) {
            int idx = secureRandom.nextInt(OTP_ID_ALPHABET.length());
            sb.append(OTP_ID_ALPHABET.charAt(idx));
        }
        return sb.toString();
    }

    // --- post-registration "account is being provisioned" email ---
    // Sent right after account creation, while the tenant's workspace is still being provisioned
    // in the background. sendAccountRegistrationConfirmation below is its counterpart, sent once
    // the workspace actually exists.

    public void sendAccountRegistrationInProgress(TenantsRegistration registration) {
        try {
            String htmlBody = renderAccountRegistrationInProgressHtml(registration.getCompanyEmail());
            String plainTextBody = "Thanks for registering. Your tenantOS account is being provisioned - "
                    + "we'll email you again once it's ready.";

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(registration.getCompanyEmail());
            helper.setSubject("Your tenantOS account setup is in progress");
            helper.setText(plainTextBody, htmlBody);
            mailSender.send(mimeMessage);
        } catch (Exception e) {
            log.warn(
                    "Failed to send account-registration-in-progress email for company_email {}",
                    registration.getCompanyEmail(),
                    e);
        }
    }

    private String renderAccountRegistrationInProgressHtml(String companyEmail) {
        Context context = new Context();
        context.setVariable("companyEmail", companyEmail);
        return templateEngine.process(ACCOUNT_REGISTRATION_INPROGRESS_TEMPLATE_NAME, context);
    }

    // --- "your workspace is ready" email ---
    // Sent by TenantWorkspaceProvisioningService once the tenant's Kubernetes namespace has
    // actually been created, closing the loop opened by sendAccountRegistrationInProgress.
    // Swallows failures for the same reason: by this point the workspace exists and the account
    // is usable, so a bounced notification is not worth failing (and retrying) the job over.

    public void sendAccountRegistrationConfirmation(TenantsRegistration registration) {
        try {
            String htmlBody = renderAccountRegistrationConfirmationHtml(registration.getCompanyEmail());
            String plainTextBody = "Your tenantOS workspace is ready. Sign in at " + loginUrl + ".";

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(registration.getCompanyEmail());
            helper.setSubject("Your tenantOS workspace is ready");
            helper.setText(plainTextBody, htmlBody);
            mailSender.send(mimeMessage);
        } catch (Exception e) {
            log.warn(
                    "Failed to send account-registration-confirmation email for company_email {}",
                    registration.getCompanyEmail(),
                    e);
        }
    }

    private String renderAccountRegistrationConfirmationHtml(String companyEmail) {
        Context context = new Context();
        context.setVariable("companyEmail", companyEmail);
        context.setVariable("loginUrl", loginUrl);
        return templateEngine.process(ACCOUNT_REGISTRATION_CONFIRMATION_TEMPLATE_NAME, context);
    }
}
