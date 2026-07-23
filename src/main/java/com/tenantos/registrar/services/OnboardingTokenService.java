package com.tenantos.registrar.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenantos.registrar.entity.Onboarding;
import com.tenantos.registrar.entity.OnboardingOtp;
import com.tenantos.registrar.entity.OnboardingToken;
import com.tenantos.registrar.entity.OnboardingTokenRequest;
import com.tenantos.registrar.exceptions.InvalidOnboardingTokenException;
import com.tenantos.registrar.exceptions.InvalidOtpException;
import com.tenantos.registrar.repository.OnboardingOtpRepository;
import com.tenantos.registrar.repository.OnboardingRepository;
import com.tenantos.registrar.repository.OnboardingTokenRepository;
import com.tenantos.registrar.repository.OnboardingTokenRequestRepository;
import com.tenantos.registrar.security.HashUtils;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Issues and consumes short-lived, single-use preflight tokens that gate every
 * endpoint under /onboarding. Kept entirely separate from JwtProvider/JwtAuthenticationFilter:
 * this token only ever grants ROLE_ONBOARDING (see OnboardingTokenAuthenticationFilter), never
 * ROLE_USER or access outside the /onboarding path.
 */
@Service
public class OnboardingTokenService {

    @Value("${fe.onboarding.session-token-cookie-name:onboarding_token}")
    @Getter
    private String onboardingSessionTokenCookieName;

    @Value("${fe.onboarding.token-ttl-seconds:900}")
    @Getter
    private long ttlSeconds;

    @Value("${fe.onboarding.otp-max-attempts:5}")
    private int otpMaxAttempts;

    private final OnboardingTokenRepository repository;
    private final OnboardingTokenRequestRepository requestRepository;
    private final OnboardingOtpRepository otpRepository;
    private final OnboardingRepository onboardingRepository;
    private final OtpAttemptTracker otpAttemptTracker;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom secureRandom = new SecureRandom();

    public OnboardingTokenService(OnboardingTokenRepository repository,
                                   OnboardingTokenRequestRepository requestRepository,
                                   OnboardingOtpRepository otpRepository,
                                   OnboardingRepository onboardingRepository,
                                   OtpAttemptTracker otpAttemptTracker) {
        this.repository = repository;
        this.requestRepository = requestRepository;
        this.otpRepository = otpRepository;
        this.onboardingRepository = onboardingRepository;
        this.otpAttemptTracker = otpAttemptTracker;
    }

    /**
     * Input to validateOtp - the service defines its own command type rather than
     * taking companyEmail/type/code as three positional strings.
     */
    public record OtpValidationCommand(String companyEmail, String type, String code) {}

    /**
     * Issues a new preflight token and logs the requesting client's browser details
     * (whatever's non-null) as a JSON blob, so new fields can be captured later without
     * a schema change.
     */
    @Transactional
    public String issue(Map<String, Object> clientDetails) {
        byte[] raw = new byte[32];
        secureRandom.nextBytes(raw);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String tokenHash = hash(rawToken);

        Instant now = Instant.now();
        OnboardingToken token = OnboardingToken.builder()
                .tokenHash(tokenHash)
                .createdAt(now)
                .expiresAt(now.plusSeconds(ttlSeconds))
                .build();
        repository.save(token);

        OnboardingTokenRequest request = OnboardingTokenRequest.builder()
                .tokenHash(tokenHash)
                .clientDetails(toJson(clientDetails))
                .requestedAt(now)
                .build();
        requestRepository.save(request);

        return rawToken;
    }

    @Transactional
    public void validateAndConsume(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidOnboardingTokenException("Missing onboarding token");
        }
        int updated = repository.consume(hash(rawToken), Instant.now());
        if (updated != 1) {
            throw new InvalidOnboardingTokenException("Onboarding token is invalid, expired, or already used");
        }
    }

    /**
     * Validates the OTP code emailed during onboarding (see OtpEmailService). Distinct
     * from the preflight session token above - this checks the 6-digit code the user
     * types in, not the httpOnly cookie. Lives here because the whole /onboarding funnel's
     * "token" concepts are consolidated in this service.
     *
     * The @Transactional on this method covers markValidated/onboarding.save below (its
     * @Modifying query needs an active transaction to run at all). The attempt count is
     * deliberately recorded through otpAttemptTracker instead of directly, since that
     * runs in its own REQUIRES_NEW transaction that commits independently - otherwise a
     * wrong code throwing from within *this* transaction would roll the increment back
     * along with it, silently defeating the attempts cap.
     */
    @Transactional
    public void validateOtp(OtpValidationCommand command) {
        Onboarding onboarding = onboardingRepository.findById(command.companyEmail())
                .orElseThrow(() -> new InvalidOtpException("No onboarding record for this company_email"));

        if (!onboarding.getOtpType().equals(command.type())) {
            throw new InvalidOtpException("otpType mismatch for this company_email");
        }
        if (!"otp-validation".equals(onboarding.getStatus())) {
            throw new InvalidOtpException("Onboarding is not awaiting OTP validation");
        }

        Instant now = Instant.now();
        int attempted = otpAttemptTracker.recordAttempt(command.companyEmail(), now, otpMaxAttempts);
        if (attempted != 1) {
            throw new InvalidOtpException("OTP code is expired or too many attempts have been made");
        }

        OnboardingOtp otp = otpRepository.findById(command.companyEmail())
                .orElseThrow(() -> new InvalidOtpException("No OTP code was issued for this company_email"));
        if (!otp.getCodeHash().equals(hash(command.code()))) {
            throw new InvalidOtpException("Invalid OTP code");
        }

        int validated = otpRepository.markValidated(command.companyEmail(), now);
        if (validated != 1) {
            throw new InvalidOtpException("OTP code was already validated");
        }

        onboarding.setStatus("active");
        onboardingRepository.save(onboarding);
    }

    /**
     * Read-only check used by OnboardingTokenAuthenticationFilter to gate every request
     * under /onboarding. Deliberately doesn't consume the token — burning it on the
     * actual write happens via validateAndConsume, so a non-mutating endpoint checked
     * only by this method (if one is ever added under /onboarding) can't drain it.
     */
    @Transactional(readOnly = true)
    public boolean isValid(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return false;
        }
        Instant now = Instant.now();
        return repository.findById(hash(rawToken))
                .filter(token -> token.getUsedAt() == null && token.getExpiresAt().isAfter(now))
                .isPresent();
    }

    /**
     * Shared cookie-extraction logic so the configurable cookie name only lives here,
     * not duplicated across the controller and OnboardingTokenAuthenticationFilter.
     */
    public String extractToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (onboardingSessionTokenCookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String toJson(Map<String, Object> clientDetails) {
        try {
            return objectMapper.writeValueAsString(clientDetails);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String hash(String rawToken) {
        return HashUtils.sha256Hex(rawToken);
    }
}
