package com.tenantos.registrar.services.tenant.steps.data_provision;

import com.tenantos.registrar.entity.AuditLog;
import com.tenantos.registrar.entity.Role;
import com.tenantos.registrar.entity.TenantMember;
import com.tenantos.registrar.entity.TenantWorkspaceProvisioning;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.entity.UserRole;
import com.tenantos.registrar.enums.AuditEventType;
import com.tenantos.registrar.enums.MembershipStatus;
import com.tenantos.registrar.enums.ProvisioningStep;
import com.tenantos.registrar.enums.SystemRole;
import com.tenantos.registrar.repository.AuditLogRepository;
import com.tenantos.registrar.repository.RoleRepository;
import com.tenantos.registrar.repository.TenantMemberRepository;
import com.tenantos.registrar.repository.UserRoleRepository;
import com.tenantos.registrar.services.tenant.AbstractTransactionalStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Step 3: make the registering user the tenant's Owner - an active membership plus the Owner role
 * assignment.
 *
 * <p>Both are created here rather than split across steps because a membership without a role, or
 * a role without a membership, is a half-provisioned tenant nobody can administer. The transaction
 * this runs in guarantees they arrive together.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CreateMembershipStep extends AbstractTransactionalStep {

  private final TenantMemberRepository tenantMemberRepository;
  private final RoleRepository roleRepository;
  private final UserRoleRepository userRoleRepository;
  private final AuditLogRepository auditLogRepository;

  @Override
  public ProvisioningStep step() {
    return ProvisioningStep.CREATE_MEMBERSHIP;
  }

  @Override
  protected void doExecute(TenantWorkspaceProvisioning job, TenantsRegistration registration) {
    log.info("Creating membership for tenant {}", registration.getTenantId());
    UUID tenantId = requireTenantId(registration);
    UUID userId = requireUserId(registration);

    Instant now = Instant.now();

    tenantMemberRepository.save(
        TenantMember.builder()
            .tenantId(tenantId)
            .userId(userId)
            .role(SystemRole.OWNER.name())
            .status(MembershipStatus.ACTIVE)
            // invitedBy stays null - nobody invited them, they created the tenant.
            .joinedAt(now)
            .build());

    Role ownerRole =
        roleRepository
            .findByTenantIdAndName(tenantId, SystemRole.OWNER.name())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Owner role missing for tenant " + tenantId + " - SEED_RBAC did not run"));

    userRoleRepository.save(
        UserRole.builder()
            .id(new UserRole.UserRoleId(userId, ownerRole.getId()))
            .tenantId(tenantId)
            .assignedAt(now)
            .build());

    auditLogRepository.save(
        AuditLog.builder()
            .tenantId(tenantId)
            .actorUserId(userId)
            .eventType(AuditEventType.OWNER_ASSIGNED)
            .resourceType("tenant_member")
            .resourceId(String.valueOf(userId))
            .payload("{\"role\":\"OWNER\"}")
            .build());

    log.info("Assigned user {} as OWNER of tenant {}", userId, tenantId);
  }
}
