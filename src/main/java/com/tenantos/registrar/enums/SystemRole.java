package com.tenantos.registrar.enums;

/**
 * The five roles seeded into every new tenant. Tenant-scoped rows in the roles table, so a tenant
 * can later add its own; these are the ones provisioning guarantees exist.
 */
public enum SystemRole {
  OWNER("Owner", "Full control of the tenant, including billing and deletion"),
  ADMINISTRATOR("Administrator", "Full control except billing management"),
  MANAGER("Manager", "Manages day-to-day operational resources"),
  MEMBER("Member", "Works with products and orders"),
  VIEWER("Viewer", "Read-only access to everything");

  private final String displayName;
  private final String description;

  SystemRole(String displayName, String description) {
    this.displayName = displayName;
    this.description = description;
  }

  public String displayName() {
    return displayName;
  }

  public String description() {
    return description;
  }
}
