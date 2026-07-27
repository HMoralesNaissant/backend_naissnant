package com.tenantos.registrar.entity;

import com.tenantos.registrar.enums.OnboardingOtpStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/**
 * The OTP code emailed during onboarding verification. Keyed by company_email like the
 * rest of the onboarding tables - one active code per onboarding attempt.
 */
@Entity
@Table(name = "onboarding_otp")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class OnboardingOtp extends BaseAuditFields {

    @Id
    @Column(name = "otp_id", length = 200, nullable = false)
    private String otpId;

    @Column(name = "company_email", length = 200, nullable = false)
    private String companyEmail;

    @Column(name = "code_hash", length = 64, nullable = false)
    private String codeHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Default
    private OnboardingOtpStatus status = OnboardingOtpStatus.CREATED;

    @Column(name = "attempts", nullable = false)
    @Default
    private int attempts = 0;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    // Set explicitly by OnboardingOtpRepository#markValidated when the code is confirmed -
    // not driven by @LastModifiedDate, since that now tracks the inherited updatedAt instead.
    @Column(name = "validated_at")
    private Instant validatedAt;
}
