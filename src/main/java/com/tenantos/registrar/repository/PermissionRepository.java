package com.tenantos.registrar.repository;

import com.tenantos.registrar.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * The catalog is a fixed 50 rows seeded by the V3 migration, so the RBAC provisioning step loads it
 * wholesale with findAll() rather than querying per role.
 */
@Repository
public interface PermissionRepository extends JpaRepository<Permission, UUID> {

  Optional<Permission> findByCode(String code);
}
