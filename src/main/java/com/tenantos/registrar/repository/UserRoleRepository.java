package com.tenantos.registrar.repository;

import com.tenantos.registrar.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserRoleRepository extends JpaRepository<UserRole, UserRole.UserRoleId> {

  /**
   * Role names a user holds in a tenant, for the JWT's authorities claim. Returns names rather
   * than entities because that is all the token needs.
   */
  @Query(
      "select r.name from UserRole ur join Role r on r.id = ur.id.roleId "
          + "where ur.id.userId = :userId and ur.tenantId = :tenantId")
  List<String> findRoleNames(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId);
}
