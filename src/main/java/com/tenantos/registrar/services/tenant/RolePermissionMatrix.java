package com.tenantos.registrar.services.tenant;

import com.tenantos.registrar.enums.SystemRole;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Which permissions each seeded role receives. Expressed as predicates over a permission's
 * {@code resource} and {@code action} rather than 200-odd hardcoded {@code resource:action}
 * strings, so adding a resource to the catalog in a future migration extends every role correctly
 * without anyone having to remember to update this file.
 */
public final class RolePermissionMatrix {

  private static final String MANAGE = "manage";
  private static final String READ = "read";
  private static final Set<String> WRITE_ACTIONS = Set.of("read", "create", "update");

  private static final Set<String> OPERATIONAL =
      Set.of("products", "orders", "inventory", "reports");
  private static final Set<String> SALES = Set.of("products", "orders");

  private RolePermissionMatrix() {}

  /** True when the role should be granted the permission. */
  public static boolean grants(SystemRole role, String resource, String action) {
    return matcherFor(role).test(new Grant(resource, action));
  }

  private static Predicate<Grant> matcherFor(SystemRole role) {
    return switch (role) {
        // Everything, including tenant deletion and billing.
      case OWNER -> g -> true;

        // Everything operational, but cannot change billing - the one power reserved to the owner.
      case ADMINISTRATOR -> g -> !("billing".equals(g.resource) && MANAGE.equals(g.action));

        // Runs the business: full read/write on operational resources, visibility into who is in
        // the tenant and how it is configured, but no authority to change either.
      case MANAGER ->
          g ->
              (OPERATIONAL.contains(g.resource) && WRITE_ACTIONS.contains(g.action))
                  || (Set.of("users", "settings").contains(g.resource) && READ.equals(g.action));

        // Day-to-day work on products and orders; can see stock levels and reports but not alter
        // them.
      case MEMBER ->
          g ->
              (SALES.contains(g.resource) && WRITE_ACTIONS.contains(g.action))
                  || (Set.of("inventory", "reports").contains(g.resource) && READ.equals(g.action));

        // Read-only across the board. Deliberately includes billing and api_keys reads - a viewer
        // sees the tenant as it is, and neither exposes a secret (api_keys never stores plaintext).
      case VIEWER -> g -> READ.equals(g.action);
    };
  }

  private record Grant(String resource, String action) {}
}
