package com.tenantos.registrar.repository;

import com.tenantos.registrar.entity.TenantWorkspaceProvisioning;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface TenantWorkspaceProvisioningRepository
    extends JpaRepository<TenantWorkspaceProvisioning, String> {

  Optional<TenantWorkspaceProvisioning> findByCompanyEmail(String companyEmail);

  /**
   * Claims a batch of jobs for this worker. FOR UPDATE SKIP LOCKED is what makes the poller safe
   * to run on every replica: concurrent workers step over each other's locked rows instead of
   * blocking or double-provisioning.
   *
   * <p>Picks up two kinds of row - jobs due for their next attempt, and jobs left IN_PROGRESS by a
   * worker that died before finishing (claimed_at older than the lease). Re-running the latter is
   * safe because TenantWorkspaceInitialization uses serverSideApply, so re-applying an already
   * created namespace is a no-op.
   *
   * <p>Native query: FOR UPDATE SKIP LOCKED has no JPQL equivalent.
   */
  @Query(
      value =
          """
          SELECT * FROM tenant_workspace_provisioning
           WHERE attempts < max_attempts
             AND ((status = 'WORKSPACE_PENDING'     AND next_attempt_at <= :now)
               OR (status = 'WORKSPACE_IN_PROGRESS' AND claimed_at < :leaseCutoff))
           ORDER BY created_at
           LIMIT :batchSize
           FOR UPDATE SKIP LOCKED
          """,
      nativeQuery = true)
  List<TenantWorkspaceProvisioning> findClaimable(
      @Param("now") Instant now,
      @Param("leaseCutoff") Instant leaseCutoff,
      @Param("batchSize") int batchSize);

  /**
   * Marks a claimed job successful. Returns rows affected (0 or 1) - same "UPDATE ... WHERE, check
   * rows-affected" pattern as OnboardingOtpRepository#recordAttempt. The caller uses a 0 here to
   * suppress the confirmation email, so a re-claimed stale lease finishing second can't send the
   * tenant a duplicate "your workspace is ready".
   */
  @Modifying
  @Query(
      "update TenantWorkspaceProvisioning p "
          + "set p.status = WORKSPACE_READY, p.namespace = :namespace, p.completedAt = :now, "
          + "p.lastError = null "
          + "where p.provisioningId = :id and p.status <> WORKSPACE_READY")
  int markReady(
      @Param("id") String provisioningId,
      @Param("namespace") String namespace,
      @Param("now") Instant now);
}
