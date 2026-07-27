package com.tenantos.registrar.entity;

import com.tenantos.registrar.enums.OnboardingStatus;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity mapping for the onboarding table.
 */
@Entity
@Table(name = "onboarding")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class Onboarding extends BaseAuditFields {

    @Id
    @Column(name = "company_email", length = 200, nullable = false)
    private String companyEmail;

    @Column(name = "otp_type", length = 50, nullable = false)
    @Default
    private String otpType = "code";

    // Store JSON payload as text; JdbcTypeCode tells Hibernate to bind/read this column as JSON
    // rather than varchar, which Postgres rejects without an explicit cast
    @Column(name = "otp_details", columnDefinition = "json", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    @Default
    private String otpDetails = "{}";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Default
    private OnboardingStatus status = OnboardingStatus.PENDING;
}
