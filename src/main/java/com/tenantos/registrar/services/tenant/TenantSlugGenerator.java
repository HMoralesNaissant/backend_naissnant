package com.tenantos.registrar.services.tenant;

import com.tenantos.registrar.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a user-supplied account name into a tenant slug: normalized, collision-free, and valid as
 * an RFC 1123 DNS label so it can be used verbatim as the tenant's Kubernetes namespace suffix.
 *
 * <p>The normalization rules moved here from {@code TenantWorkspaceInitialization}, which used to
 * sanitize the account name itself on every provisioning run. Doing it once at tenant creation and
 * persisting the result to {@code tenants.slug} fixes a real defect in that approach: two accounts
 * named "Acme" sanitized to the same namespace, and nothing detected the clash. The slug column is
 * UNIQUE, so the suffix loop below resolves collisions before they reach Kubernetes.
 */
@Service
@RequiredArgsConstructor
public class TenantSlugGenerator {

  private static final Pattern NON_LABEL_CHARS = Pattern.compile("[^a-z0-9-]+");
  private static final Pattern LEADING_TRAILING_HYPHENS = Pattern.compile("^-+|-+$");
  private static final int MAX_SLUG_LENGTH = 63;
  private static final int MAX_COLLISION_ATTEMPTS = 1000;

  private final TenantRepository tenantRepository;

  /**
   * Generates a slug that is free at the time of the call. Still racy against a concurrent insert
   * by design - the UNIQUE constraint on {@code tenants.slug} is the real arbiter, and a losing
   * insert fails its provisioning step and gets retried, picking up the next free suffix.
   */
  public String generate(String accountName, String fallbackSeed) {
    String base = normalize(accountName);
    if (base.isBlank()) {
      // Nothing usable survived normalization (e.g. an account name of only punctuation, or a
      // non-Latin script). Fall back to a stable hash of the email so the slug is still
      // deterministic for a given tenant rather than random.
      base = "tenant-" + Integer.toHexString(fallbackSeed.hashCode());
    }

    if (!tenantRepository.existsBySlug(base)) {
      return base;
    }

    for (int suffix = 2; suffix < MAX_COLLISION_ATTEMPTS; suffix++) {
      String candidate = withSuffix(base, suffix);
      if (!tenantRepository.existsBySlug(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("Could not find a free slug for account name: " + accountName);
  }

  /** Lowercase, non-label characters collapsed to hyphens, trimmed, capped at 63 characters. */
  public String normalize(String value) {
    if (value == null) {
      return "";
    }
    String collapsed =
        NON_LABEL_CHARS.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("-");
    String trimmed = LEADING_TRAILING_HYPHENS.matcher(collapsed).replaceAll("");
    return truncate(trimmed);
  }

  /**
   * Appends {@code -N}, shortening the base first when needed so the result still fits in 63
   * characters - truncating afterwards would cut the suffix back off and defeat the whole point.
   */
  private String withSuffix(String base, int suffix) {
    String tail = "-" + suffix;
    int room = MAX_SLUG_LENGTH - tail.length();
    String head = base.length() > room ? base.substring(0, room) : base;
    return LEADING_TRAILING_HYPHENS.matcher(head).replaceAll("") + tail;
  }

  private String truncate(String value) {
    if (value.length() <= MAX_SLUG_LENGTH) {
      return value;
    }
    // Re-trim: truncation can leave a trailing hyphen, which is not a valid DNS label.
    return LEADING_TRAILING_HYPHENS
        .matcher(value.substring(0, MAX_SLUG_LENGTH))
        .replaceAll("");
  }
}
