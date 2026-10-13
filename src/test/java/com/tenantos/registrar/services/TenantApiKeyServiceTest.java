package com.tenantos.registrar.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tenantos.registrar.entity.ApiKey;
import com.tenantos.registrar.entity.Tenant;
import com.tenantos.registrar.entity.TenantMember;
import com.tenantos.registrar.entity.User;
import com.tenantos.registrar.enums.MembershipStatus;
import com.tenantos.registrar.repository.*;
import com.tenantos.registrar.services.auth.TenantApiKeyService;
import com.tenantos.registrar.utils.HashUtils;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantApiKeyServiceTest {

  ApiKeyRepository keys = mock(ApiKeyRepository.class);
  TenantRepository tenants = mock(TenantRepository.class);
  UserRepository users = mock(UserRepository.class);
  TenantMemberRepository members = mock(TenantMemberRepository.class);
  TenantApiKeyService service = new TenantApiKeyService(keys, tenants, users, members);

  UUID tenantId = UUID.randomUUID();

  ApiKey key(Instant revoked, Instant expires, String scopes) {
    ApiKey k =
        ApiKey.builder().tenantId(tenantId).keyHash("h").scopes(scopes).build();
    k.setRevokedAt(revoked);
    k.setExpiresAt(expires);
    when(keys.findByKeyHash(HashUtils.sha256Hex("secret"))).thenReturn(Optional.of(k));
    return k;
  }

  @Test
  void acceptsAValidInternalKeyAndStampsLastUsed() {
    ApiKey k = key(null, null, "[\"internal:*\"]");
    assertTrue(service.authenticate("secret").isPresent());
    assertTrue(k.getLastUsedAt() != null);
  }

  @Test
  void rejectsRevokedExpiredNonInternalUnknownAndBlank() {
    key(Instant.now(), null, "[\"internal:*\"]");
    assertTrue(service.authenticate("secret").isEmpty());
    key(null, Instant.now().minusSeconds(5), "[\"internal:*\"]");
    assertTrue(service.authenticate("secret").isEmpty());
    key(null, null, "[\"read\"]");
    assertTrue(service.authenticate("secret").isEmpty());
    assertTrue(service.authenticate("other").isEmpty());
    assertTrue(service.authenticate(" ").isEmpty());
    assertTrue(service.authenticate(null).isEmpty());
  }

  @Test
  void resolvesSlugOnlyForActiveMembersOfTheKeysTenant() {
    ApiKey k = key(null, null, "[\"internal:*\"]");
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);
    when(users.findByEmail("a@acme.io")).thenReturn(Optional.of(user));
    TenantMember member =
        TenantMember.builder().tenantId(tenantId).userId(userId).status(MembershipStatus.ACTIVE).build();
    when(members.findByTenantIdAndUserId(tenantId, userId)).thenReturn(Optional.of(member));
    when(tenants.findById(tenantId))
        .thenReturn(Optional.of(Tenant.builder().id(tenantId).slug("acme").build()));

    assertEquals(Optional.of("acme"), service.tenantSlugForMember(k, " a@acme.io "));
    assertTrue(service.tenantSlugForMember(k, "nobody@acme.io").isEmpty());
  }
}
