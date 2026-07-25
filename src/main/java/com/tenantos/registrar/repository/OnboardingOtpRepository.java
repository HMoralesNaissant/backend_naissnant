package com.tenantos.registrar.repository;

import com.tenantos.registrar.entity.OnboardingOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface OnboardingOtpRepository extends JpaRepository<OnboardingOtp, String> {


    Optional<OnboardingOtp> findByCompanyEmail(String companyEmail);

    Optional<OnboardingOtp> findByOtpIdAndCompanyEmail(String otpId, String companyEmail);

    /**
     * Atomically records a validation attempt, only if the OTP is still pending, unexpired,
     * and under the attempt cap. Returns the number of rows updated (0 or 1), same
     * "UPDATE ... WHERE, check rows-affected" pattern as OnboardingTokenRepository#consume -
     * avoids a lost-update race on `attempts` under concurrent validate calls.
     */
    @Modifying
    @Query("update OnboardingOtp o set o.attempts = o.attempts + 1 " +
            "where o.companyEmail = :email and o.status = 'created' " +
            "and o.expiresAt > :now and o.attempts < :maxAttempts")
    int recordAttempt(@Param("email") String email, @Param("now") Instant now, @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Query("update OnboardingOtp o set o.status = 'validated', o.validatedAt = :now " +
            "where o.companyEmail = :email and o.status = 'created'")
    int markValidated(@Param("email") String email, @Param("now") Instant now);

    @Modifying
    @Query("update OnboardingOtp o set o.status = 'invalidated', o.validatedAt = :now " +
            "where o.companyEmail = :email and o.status = 'created'")
    void markInvalidated(@Param("email") String email, @Param("now") Instant now);
}
