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

    // Column is named "password" per the existing migration, but this always holds a
    // BCrypt hash (via the PasswordEncoder bean in SecurityConfig) - never plaintext.
    @Column(name = "password", length = 200)
    private String password;

    @Column(name = "account_name", length = 100)
    private String accountName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Default
    private TenantsRegistrationStatus status = TenantsRegistrationStatus.ONBOARDING;
}
