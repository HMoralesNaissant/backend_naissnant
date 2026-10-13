package com.tenantos.registrar.services.tenant.steps.data_provision;

import com.tenantos.registrar.entity.ApiKey;
import com.tenantos.registrar.entity.Tenant;
import com.tenantos.registrar.repository.TenantRepository;
import com.tenantos.registrar.services.infisical.TenantInfisicalSecretWriter;
import com.tenantos.registrar.entity.AuditLog;
import com.tenantos.registrar.entity.TenantWorkspaceProvisioning;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.enums.AuditEventType;
import com.tenantos.registrar.enums.ProvisioningStep;
import com.tenantos.registrar.repository.ApiKeyRepository;
import com.tenantos.registrar.repository.AuditLogRepository;
import com.tenantos.registrar.services.tenant.AbstractTransactionalStep;
import com.tenantos.registrar.utils.HashUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Step 5: mint the tenant's bootstrap API key.
 *
 * <p>This is an <em>internal</em> service credential, not a user-facing one, and its plaintext is
 * discarded once hashed - except for one hand-off: when Infisical is enabled it is written to the
 * tenant's app folders so the deployed containers can read it. A background job has no caller to
 * hand a secret back to, and neither of the other channels is fit to carry one: the confirmation
 * email would put a live credential in a mailbox, and the provisioning status endpoint is
 * unauthenticated. User-facing key issuance belongs behind an authenticated endpoint that can
 * return the plaintext once, in the response, to a caller who has proven who they are.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CreateApiKeyStep extends AbstractTransactionalStep {

  private static final String KEY_NAME = "bootstrap-internal";
  private static final String KEY_PREFIX_LITERAL = "tos_int_";
  private static final String KEY_ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  private static final int KEY_BODY_LENGTH = 48;
  private static final String INTERNAL_SCOPES = "[\"internal:*\"]";

  private final ApiKeyRepository apiKeyRepository;
  private final AuditLogRepository auditLogRepository;
  private final TenantRepository tenantRepository;
  private final TenantInfisicalSecretWriter tenantInfisicalSecretWriter;
  private final SecureRandom secureRandom = new SecureRandom();

  @Override
  public ProvisioningStep step() {
    return ProvisioningStep.CREATE_API_KEY;
  }

  @Override
  protected void doExecute(TenantWorkspaceProvisioning job, TenantsRegistration registration) {
    log.info("Creating bootstrap API key for tenant {}", registration.getTenantId());
    UUID tenantId = requireTenantId(registration);
    UUID actorId = requireUserId(registration);

    String plaintext = KEY_PREFIX_LITERAL + randomBody();
    // Never logged or returned; its only exit is the Infisical write below.
    String keyHash = HashUtils.sha256Hex(plaintext);

    // Handed to Infisical before the row is saved: if that fails the transaction rolls back and the
    // retry mints a fresh key, so a stored hash never exists without a delivered key.
    Tenant tenant =
        tenantRepository
            .findById(tenantId)
            .orElseThrow(() -> new IllegalStateException("Tenant " + tenantId + " not found"));
    tenantInfisicalSecretWriter.writeApiKey(tenant, plaintext);

    ApiKey apiKey =
        apiKeyRepository.save(
            ApiKey.builder()
                .tenantId(tenantId)
                .name(KEY_NAME)
                .keyPrefix(plaintext.substring(0, 12))
                .keyHash(keyHash)
                .scopes(INTERNAL_SCOPES)
                .createdBy(actorId)
                .build());

    auditLogRepository.save(
        AuditLog.builder()
            .tenantId(tenantId)
            .actorUserId(actorId)
            .eventType(AuditEventType.API_KEY_CREATED)
            .resourceType("api_key")
            .resourceId(String.valueOf(apiKey.getId()))
            .payload("{\"name\":\"" + KEY_NAME + "\"}")
            .build());

    log.info("Created bootstrap API key {} for tenant {}", apiKey.getKeyPrefix(), tenantId);
  }

  private String randomBody() {
    StringBuilder sb = new StringBuilder(KEY_BODY_LENGTH);
    for (int i = 0; i < KEY_BODY_LENGTH; i++) {
      sb.append(KEY_ALPHABET.charAt(secureRandom.nextInt(KEY_ALPHABET.length())));
    }
    return sb.toString();
  }
}
