package com.tenantos.registrar.repository;

import com.tenantos.registrar.entity.TenantMember;
import com.tenantos.registrar.enums.MembershipStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantMemberRepository extends JpaRepository<TenantMember, UUID> {

  /** Login uses this to resolve which tenant(s) a user can sign in to. */
  List<TenantMember> findByUserIdAndStatus(UUID userId, MembershipStatus status);

  Optional<TenantMember> findByTenantIdAndUserId(UUID tenantId, UUID userId);
}
