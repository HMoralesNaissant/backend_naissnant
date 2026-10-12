package com.tenantos.registrar.controllers;

import com.tenantos.registrar.domain.response.TenantTeardownResponse;
import com.tenantos.registrar.services.tenant.TenantTeardownService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Operator-only endpoints. Authenticated by a shared secret in {@code X-Admin-Token} rather than a
 * tenant JWT or the onboarding cookie: any signed-in tenant member could otherwise delete someone
 * else's tenant, and the onboarding cookie is handed to anyone who asks.
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class TenantAdminController {

  private final TenantTeardownService tenantTeardownService;

  /** Blank disables the endpoint entirely. */
  @Value("${admin.api-token:}")
  private String adminToken;

  @Operation(
      summary = "Destroy a tenant and every resource provisioned for it",
      description =
          "Deletes the Kubernetes namespace (and the deployed apps), the tenant database and role, "
              + "the Infisical folder, and the tenant's registrar records, user and onboarding rows. "
              + "Irreversible. The account name is matched on its slug, so \"Acme Inc\" and "
              + "\"acme-inc\" are the same tenant. Safe to repeat after a partial failure. "
              + "Requires the X-Admin-Token header; 404 when the endpoint is not enabled.")
  @DeleteMapping("/tenants")
  public ResponseEntity<?> destroyTenant(
      @RequestHeader(value = "X-Admin-Token", required = false) String token,
      @RequestParam("accountName") String accountName) {
    if (adminToken == null || adminToken.isBlank()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
    if (token == null || !constantTimeEquals(adminToken, token)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    try {
      TenantTeardownResponse result = tenantTeardownService.destroy(accountName);
      return ResponseEntity.ok(result);
    } catch (NoSuchElementException e) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
  }

  private static boolean constantTimeEquals(String expected, String actual) {
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }
}
