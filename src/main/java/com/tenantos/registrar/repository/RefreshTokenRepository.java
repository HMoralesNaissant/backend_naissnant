package com.tenantos.registrar.repository;

import com.tenantos.registrar.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  /** Lookup is by hash - the raw token is never stored, so it's the only handle we have. */
  Optional<RefreshToken> findByTokenHash(String tokenHash);

  /**
   * Revokes every unrevoked token belonging to a user. The reuse-detection response: presenting an
   * already-rotated token means a copy is circulating, so the whole family is burned rather than
   * just the replayed link.
   */
  @Modifying
  @Query(
      "update RefreshToken t set t.revokedAt = :now "
          + "where t.userId = :userId and t.revokedAt is null")
  int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

  @Modifying
  @Query(
      "update RefreshToken t set t.revokedAt = :now "
          + "where t.sessionId = :sessionId and t.revokedAt is null")
  int revokeBySession(@Param("sessionId") UUID sessionId, @Param("now") Instant now);
}
