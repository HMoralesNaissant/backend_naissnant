package com.tenantos.registrar.services.tenant.steps.data_provision;

import com.tenantos.registrar.entity.AuditLog;
import com.tenantos.registrar.entity.Permission;
import com.tenantos.registrar.entity.Role;
import com.tenantos.registrar.entity.RolePermission;
import com.tenantos.registrar.entity.TenantWorkspaceProvisioning;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.enums.AuditEventType;
import com.tenantos.registrar.enums.ProvisioningStep;
import com.tenantos.registrar.enums.SystemRole;
import com.tenantos.registrar.repository.AuditLogRepository;
import com.tenantos.registrar.repository.PermissionRepository;
import com.tenantos.registrar.repository.RolePermissionRepository;
import com.tenantos.registrar.repository.RoleRepository;
import com.tenantos.registrar.services.tenant.AbstractTransactionalStep;
import com.tenantos.registrar.services.tenant.RolePermissionMatrix;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Step 2: give the tenant its five system roles and wire each to the permissions it should hold.
 *
 * <p>Permissions themselves are not created here - they are a global catalog seeded once by the V3
 * migration. This step only creates tenant-scoped roles and the role_permissions grants that draw
 * from that catalog.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SeedRbacStep extends AbstractTransactionalStep {

  private final RoleRepository roleRepository;
  private final PermissionRepository permissionRepository;
  private final RolePermissionRepository rolePermissionRepository;
  private final AuditLogRepository auditLogRepository;

  @Override
  public ProvisioningStep step() {
    return ProvisioningStep.SEED_RBAC;
  }

  @Override
  protected void doExecute(TenantWorkspaceProvisioning job, TenantsRegistration registration) {
    log.info("Seeding RBAC for tenant {}", registration.getTenantId());
    UUID tenantId = requireTenantId(registration);
    UUID actorId = requireUserId(registration);

    // 50 rows, loaded once and reused across all five roles rather than re-queried per role.
    List<Permission> catalog = permissionRepository.findAll();
    if (catalog.isEmpty()) {
      throw new IllegalStateException(
          "Permission catalog is empty - the V3 migration seed has not been applied");
    }

    List<RolePermission> grants = new ArrayList<>();
    for (SystemRole systemRole : SystemRole.values()) {
      Role role =
          roleRepository.save(
              Role.builder()
                  .tenantId(tenantId)
                  .name(systemRole.name())
                  .description(systemRole.description())
                  .systemRole(true)
                  .build());

      for (Permission permission : catalog) {
        if (RolePermissionMatrix.grants(systemRole, permission.getResource(), permission.getAction())) {
          grants.add(
              RolePermission.builder()
                  .id(new RolePermission.RolePermissionId(role.getId(), permission.getId()))
                  .build());
        }
      }

      auditLogRepository.save(
          AuditLog.builder()
              .tenantId(tenantId)
              .actorUserId(actorId)
              .eventType(AuditEventType.ROLE_PROVISIONED)
              .resourceType("role")
              .resourceId(String.valueOf(role.getId()))
              .payload("{\"name\":\"" + systemRole.name() + "\"}")
              .build());
    }

    rolePermissionRepository.saveAll(grants);

    auditLogRepository.save(
        AuditLog.builder()
            .tenantId(tenantId)
            .actorUserId(actorId)
            .eventType(AuditEventType.PERMISSION_PROVISIONED)
            .resourceType("role_permissions")
            .payload("{\"grants\":" + grants.size() + ",\"catalog\":" + catalog.size() + "}")
            .build());

    log.info(
        "Seeded {} roles and {} permission grants for tenant {}",
        SystemRole.values().length,
        grants.size(),
        tenantId);
  }
}
