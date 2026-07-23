package com.tenantos.registrar.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Logs the requesting client's browser details captured each time a preflight
 * onboarding token is issued. Purely an audit trail — never read back to authorize
 * anything. clientDetails is a free-form JSON blob (see Onboarding.otpDetails for the
 * same String + @JdbcTypeCode(SqlTypes.JSON) pattern) so new fields can be added later
 * without a schema change.
 */
@Entity
@Table(name = "onboarding_token_request")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OnboardingTokenRequest {

    @Id
    @Column(name = "token_hash", length = 64, nullable = false)
    private String tokenHash;

    @Column(name = "client_details", columnDefinition = "json", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String clientDetails;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;
}
