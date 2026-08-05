package com.tenantos.registrar.repository;

import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.enums.TenantsRegistrationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TenantsRegistrationRepository extends JpaRepository<TenantsRegistration, String> {

  /**
   * Mirrors the provisioning job's status onto the registration row. A targeted update rather
   * than a load-mutate-save, since the provisioning worker has no other reason to hold the
   * registration entity.
   */
  @Modifying
  @Query(
      "update TenantsRegistration t set t.workspaceStatus = :status where t.companyEmail = :email")
  int updateWorkspaceStatus(
      @Param("email") String companyEmail, @Param("status") TenantsRegistrationStatus status);
}
