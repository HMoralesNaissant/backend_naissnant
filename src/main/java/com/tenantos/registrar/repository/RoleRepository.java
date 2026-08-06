package com.tenantos.registrar.repository;

import com.tenantos.registrar.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RoleRepository extends JpaRepository<Role, UUID> {

  List<Role> findByTenantId(UUID tenantId);

  Optional<Role> findByTenantIdAndName(UUID tenantId, String name);
}
