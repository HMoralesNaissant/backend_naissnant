package com.tenantos.registrar.repository;

import com.tenantos.registrar.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface SessionRepository extends JpaRepository<Session, UUID> {

  /**
   * Revokes every live session for a user. Used when refresh-token reuse is detected - the safe
   * response to a captured token is to sign the user out everywhere, not just on that device.
   */
  @Modifying
  @Query("update Session s set s.revokedAt = :now where s.userId = :userId and s.revokedAt is null")
  int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
