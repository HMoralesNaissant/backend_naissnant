package com.tenantos.registrar.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
@AllArgsConstructor
@Builder
public class OnboardingOtp {

    @Id
    @Column(name = "company_email", length = 200, nullable = false)
    private String companyEmail;

    @Column(name = "code_hash", length = 64, nullable = false)
    private String codeHash;

    @Column(name = "status", length = 20, nullable = false)
    @Default
    private String status = "created";

    @Column(name = "attempts", nullable = false)
    @Default
    private int attempts = 0;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "validated_at")
    private Instant validatedAt;
}
