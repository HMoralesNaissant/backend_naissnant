package com.tenantos.registrar.repository;

import com.tenantos.registrar.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

  /**
   * The uq_subscriptions_live partial index guarantees at most one TRIALING/ACTIVE row per tenant,
   * so this can safely return a single result.
   */
  Optional<Subscription> findByTenantIdAndStatusIn(
      UUID tenantId, java.util.Collection<com.tenantos.registrar.enums.SubscriptionStatus> statuses);
}
