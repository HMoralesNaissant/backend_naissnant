package com.tenantos.registrar.entity;

import com.tenantos.registrar.enums.SubscriptionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the subscriptions table (V3 migration).
 *
 * <p>Cancelled and expired rows are kept as history, but the uq_subscriptions_live partial index
 * allows only one TRIALING/ACTIVE row per tenant - which is also what stops a retried provisioning
 * step from creating a second trial.
 */
@Entity
@Table(name = "subscriptions")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class Subscription extends BaseAuditFields {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "plan", length = 30, nullable = false)
  @Default
  private String plan = "FREE_TRIAL";

  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 20, nullable = false)
  @Default
  private SubscriptionStatus status = SubscriptionStatus.TRIALING;

  @Column(name = "trial_ends_at")
  private Instant trialEndsAt;

  @Column(name = "current_period_start", nullable = false)
  @Default
  private Instant currentPeriodStart = Instant.now();

  @Column(name = "current_period_end")
  private Instant currentPeriodEnd;

  /** Plan quotas as JSON, e.g. {@code {"seats":5,"apiCallsPerMonth":100000}}. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "limits", nullable = false)
  @Default
  private String limits = "{}";
}
