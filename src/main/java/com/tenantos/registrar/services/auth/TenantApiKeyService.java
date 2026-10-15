package com.tenantos.registrar.services.auth;

import com.tenantos.registrar.entity.ApiKey;
import com.tenantos.registrar.entity.Tenant;
import com.tenantos.registrar.enums.MembershipStatus;
import com.tenantos.registrar.repository.ApiKeyRepository;
import com.tenantos.registrar.repository.TenantMemberRepository;
import com.tenantos.registrar.repository.TenantRepository;
import com.tenantos.registrar.repository.UserRepository;
import com.tenantos.registrar.utils.HashUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Authenticates a tenant's apps to the registrar by API key - the key {@code CreateApiKeyStep}
 * minted and published to their containers as {@code TENANT_API_KEY}.
 *
 * <p>A key belongs to exactly one tenant, so everything it can ask about is scoped to that tenant:
 * an email is "valid" only if it is an active member of the key's own tenant.
 */
@Service
@RequiredArgsConstructor
public class TenantApiKeyService {

  private static final String INTERNAL_SCOPE = "internal:";

  private final ApiKeyRepository apiKeyRepository;
  private final TenantRepository tenantRepository;
  private final UserRepository userRepository;
  private final TenantMemberRepository tenantMemberRepository;

  /** The key's row if it is known, unrevoked, unexpired and carries an internal scope. */
  @Transactional
  public Optional<ApiKey> authenticate(String plaintextKey) {
    if (plaintextKey == null || plaintextKey.isBlank()) {
      return Optional.empty();
    }
    Instant now = Instant.now();
    Optional<ApiKey> key =
        apiKeyRepository
            .findByKeyHash(HashUtils.sha256Hex(plaintextKey.trim()))
            .filter(k -> k.getRevokedAt() == null)
            .filter(k -> k.getExpiresAt() == null || k.getExpiresAt().isAfter(now))
            .filter(k -> k.getScopes() != null && k.getScopes().contains(INTERNAL_SCOPE));
    key.ifPresent(k -> k.setLastUsedAt(now));
    return key;
  }

  /** The key's tenant slug, if {@code email} is an active member of that tenant. */
  @Transactional(readOnly = true)
  public Optional<String> tenantSlugForMember(ApiKey key, String email) {
    return userRepository
        .findByEmail(email.trim())
        .flatMap(user -> tenantMemberRepository.findByTenantIdAndUserId(key.getTenantId(), user.getId()))
        .filter(member -> member.getStatus() == MembershipStatus.ACTIVE)
        .flatMap(member -> tenantRepository.findById(key.getTenantId()))
        .map(Tenant::getSlug);
  }

  /**
   * Temporary, key-less variant of {@link #tenantSlugForMember}: the slug of an active tenant the
   * email belongs to. A user in several tenants gets the first one found.
   */
  @Transactional(readOnly = true)
  public Optional<String> tenantSlugForEmail(String email) {
    return userRepository
        .findByEmail(email.trim())
        .flatMap(
            user ->
                tenantMemberRepository
                    .findByUserIdAndStatus(user.getId(), MembershipStatus.ACTIVE)
                    .stream()
                    .map(member -> tenantRepository.findById(member.getTenantId()))
                    .flatMap(Optional::stream)
                    .map(Tenant::getSlug)
                    .findFirst());
  }
}
