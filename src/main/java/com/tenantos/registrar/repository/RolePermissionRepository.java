package com.tenantos.registrar.repository;

import com.tenantos.registrar.entity.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RolePermissionRepository
    extends JpaRepository<RolePermission, RolePermission.RolePermissionId> {}
