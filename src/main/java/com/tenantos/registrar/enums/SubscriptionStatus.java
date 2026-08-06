package com.tenantos.registrar.enums;

/**
 * Values for the subscriptions table's status column. TRIALING and ACTIVE are the "live" states -
 * the uq_subscriptions_live partial index allows only one row per tenant in either.
 */
public enum SubscriptionStatus {
  TRIALING,
  ACTIVE,
  PAST_DUE,
  CANCELED,
  EXPIRED
}
