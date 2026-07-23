package com.tenantos.registrar.repository;

import com.tenantos.registrar.entity.OnboardingToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface OnboardingTokenRepository extends JpaRepository<OnboardingToken, String> {

    /**
     * Atomically marks a token used, only if it's still unused and unexpired.
     * Returns the number of rows updated (0 or 1) so callers can detect an
     * invalid/expired/already-consumed token without a separate read-then-write
     * race window.
     */
    @Modifying
    @Query("update OnboardingToken t set t.usedAt = :now where t.tokenHash = :hash and t.usedAt is null and t.expiresAt > :now")
    int consume(@Param("hash") String tokenHash, @Param("now") Instant now);
}
