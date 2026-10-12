package com.tenantos.registrar.services.onboarding;

import com.tenantos.registrar.domain.response.AccountNameAvailabilityResponse;
import com.tenantos.registrar.repository.TenantRepository;
import com.tenantos.registrar.services.tenant.TenantSlugGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Answers "is this account name already in use?" before the user commits to it in registration.
 *
 * <p>What identifies a tenant is its slug, not the free-text name: {@code tenants.slug} is the
 * UNIQUE column, and the namespace and hostnames are derived from it. So availability is decided on
 * the slug the name would normalize to, with the same rules {@link TenantSlugGenerator} applies at
 * creation - "Acme Inc", "acme inc" and "ACME-INC" are one name as far as this check is concerned.
 *
 * <p>Advisory, not a reservation. Nothing holds the slug between this call and registration, and a
 * registration only gets a tenant row a few steps into the provisioning pipeline, so a concurrent
 * signup can still take it. The slug generator resolves that by suffixing ({@code acme-2}), which
 * is also what {@code suggestion} previews.
 */
@Service
@RequiredArgsConstructor
public class AccountNameAvailabilityService {

  /** Matches {@code tenants_registration.account_name}. */
  static final int MAX_NAME_LENGTH = 100;

  private final TenantRepository tenantRepository;
  private final TenantSlugGenerator tenantSlugGenerator;

  /**
   * @throws IllegalArgumentException (mapped to 400) if the name is blank, too long, or has no
   *     letters or digits to build a slug from
   */
  public AccountNameAvailabilityResponse check(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name is required");
    }
    if (name.strip().length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException("name must be at most " + MAX_NAME_LENGTH + " characters");
    }

    String slug = tenantSlugGenerator.normalize(name);
    if (slug.isEmpty()) {
      // Registration would fall back to an email-derived slug here; as a name the user is picking
      // and will see, nothing useful can be said about it.
      throw new IllegalArgumentException("name must contain at least one letter or digit");
    }

    if (!tenantRepository.existsBySlug(slug)) {
      return new AccountNameAvailabilityResponse(slug, true, null);
    }
    return new AccountNameAvailabilityResponse(slug, false, tenantSlugGenerator.generate(name, slug));
  }
}
