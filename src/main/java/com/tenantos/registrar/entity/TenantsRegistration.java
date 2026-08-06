package com.tenantos.registrar.entity;

import com.tenantos.registrar.enums.TenantsRegistrationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * JPA entity mapping for the tenants_registration table (V1__initial_setup.sql).
 * Created once an onboarding record's OTP has been validated.
 */
@Entity
@Table(name = "tenants_registration")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class TenantsRegistration extends BaseAuditFields {

    @Id
    @Column(name = "company_email", length = 200, nullable = false)
    private String companyEmail;

    @Column(name = "full_name", length = 200)
    private String fullName;

    @Column(name = "account_name", length = 100)
    private String accountName;

    /**
     * Transient handoff to the CREATE_USER provisioning step, not a credential store. Bcrypt needs
     * the plaintext password, which only exists during the registration request, so register()
     * hashes it and stages the result here; CREATE_USER creates the user and nulls this in the same
     * transaction. users.password_hash is the permanent home.
     */
    @Column(name = "password_hash", length = 200)
    private String passwordHash;

    // No user_id here on purpose: users.email is UNIQUE and always equals company_email, so the
    // user is reachable by lookup and a column would only duplicate the relationship. Provisioning
    // steps resolve it via UserRepository.findByEmail(companyEmail).

    // Filled in by the CREATE_TENANT provisioning step - null until the pipeline gets there.
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Default
    private TenantsRegistrationStatus status = TenantsRegistrationStatus.ONBOARDING;

    // Denormalized copy of the tenant_workspace_provisioning row's status, kept in sync by
    // TenantWorkspaceProvisioningService. The job table remains the source of truth (it also
    // holds attempts/last_error); this exists so reading a tenant's workspace state is a
    // single-row lookup.
    @Enumerated(EnumType.STRING)
    @Column(name = "workspace_status", length = 30, nullable = false)
    @Default
    private TenantsRegistrationStatus workspaceStatus = TenantsRegistrationStatus.WORKSPACE_PENDING;
}
