package com.tenantos.registrar.enums;

/**
 * Where the tenant provisioning pipeline resumes, persisted on
 * tenant_workspace_provisioning.current_step.
 *
 * <p>Declaration order is execution order - {@link #next()} walks it - so inserting a step means
 * adding it here and to the column's CHECK constraint, nothing else.
 *
 * <p>Each database-backed step advances this value inside its own transaction, so a step either
 * commits its work together with the advance or leaves nothing behind. That is what makes retries
 * safe without per-step "have I already done this?" checks.
 */
public enum ProvisioningStep {
  CREATE_USER,
  CREATE_TENANT,
  SEED_RBAC,
  CREATE_MEMBERSHIP,
  CREATE_SUBSCRIPTION,
  CREATE_API_KEY,
  CREATE_NAMESPACE,
  SEND_CONFIRMATION,
  COMPLETED;

  /** The step that follows this one; COMPLETED is terminal and returns itself. */
  public ProvisioningStep next() {
    return this == COMPLETED ? COMPLETED : values()[ordinal() + 1];
  }
}
