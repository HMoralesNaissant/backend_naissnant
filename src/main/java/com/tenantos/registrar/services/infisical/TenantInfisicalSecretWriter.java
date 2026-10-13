package com.tenantos.registrar.services.infisical;

import com.tenantos.registrar.entity.Tenant;
import com.tenantos.registrar.services.database.ProvisionedTenantDatabase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirrors the tenant's database credential - the same values {@code TenantSecretWriter} puts in the
 * Kubernetes Secret - into the tenant's {@code /<slug>/backend} Infisical folder, keys prefixed
 * {@code TENANT_}.
 *
 * <p>Called from the database step because that is the only place the password exists. Values are
 * overwritten on every call: a retry mints a new password, and the stored one must follow it.
 *
 * <p>Also publishes what the deployed tenant backend reads, under the key names {@code
 * skaffolder-configs/configs/tenantos-backend.yml} already maps ({@code TENANT_OS_*}), so the
 * tenant-deploy workflow renders the same manifests as the platform's own backend with no secret
 * mapping of its own. The per-tenant JWT signing secrets are generated once and then left alone:
 * rotating them on a retry would invalidate every session the tenant has.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TenantInfisicalSecretWriter {

  private static final String PREFIX = "TENANT_";
  private static final String JWT_ACCESS_KEY = "TENANT_OS_JWT_ACCESS_SECRET";
  private static final String JWT_REFRESH_KEY = "TENANT_OS_JWT_REFRESH_SECRET";
  private static final int JWT_SECRET_BYTES = 48;
  private static final String API_KEY = "TENANT_OS_API_KEY";

  private final SecureRandom secureRandom = new SecureRandom();

  /** Shared by every tenant backend; blank means the deploy is not configured with one. */
  @Value("${tenant.deployment.redis-url:}")
  private String redisUrl;

  @Value("${infisical.enabled:false}")
  private boolean enabled;

  private final InfisicalClient infisicalClient;

  public void write(Tenant tenant, ProvisionedTenantDatabase database) {
    if (!enabled) {
      return;
    }
    Map<String, String> values = new LinkedHashMap<>();
    values.put(PREFIX + "HOST", database.host());
    values.put(PREFIX + "PORT", String.valueOf(database.port()));
    values.put(PREFIX + "DATABASE", database.databaseName());
    values.put(PREFIX + "USERNAME", database.roleName());
    values.put(PREFIX + "PASSWORD", database.password());
    values.put(PREFIX + "JDBC_URL", database.jdbcUrl());

    String databaseUrl = postgresUrl(database);
    values.put("TENANT_OS_DATABASE_URL", databaseUrl);
    values.put("TENANT_OS_PRISMA_DATABASE_URL", databaseUrl);
    if (!redisUrl.isBlank()) {
      values.put("TENANT_OS_REDIS_URL", redisUrl);
    }

    // Regenerated if this step is retried; harmless, as the apps are deployed after it.
    values.put(JWT_ACCESS_KEY, randomSecret());
    values.put(JWT_REFRESH_KEY, randomSecret());

    String path = "/" + tenant.getSlug() + "/backend";
    infisicalClient.ensureFolder(path);
    infisicalClient.upsertSecrets(path, values);

    // The UI maps no secrets, but skaffolder still reads its folder whenever Infisical credentials
    // are present, and a folder that does not exist fails the render.
    infisicalClient.ensureFolder("/" + tenant.getSlug() + "/ui");
    log.info("Published database credential to Infisical {} for tenant {}", path, tenant.getId());
  }

  /**
   * Publishes the tenant's bootstrap API key to both apps' folders. The registrar keeps only its
   * hash, so this is the one moment the plaintext can be handed on; skaffolder maps it into each
   * container as an env var from there. A no-op while Infisical is disabled.
   */
  public void writeApiKey(Tenant tenant, String plaintext) {
    if (!enabled) {
      return;
    }
    for (String app : List.of("backend", "ui")) {
      String path = "/" + tenant.getSlug() + "/" + app;
      infisicalClient.ensureFolder(path);
      infisicalClient.upsertSecrets(path, Map.of(API_KEY, plaintext));
    }
    log.info("Published API key to Infisical for tenant {}", tenant.getId());
  }

  /** What Prisma and the backend's own DATABASE_URL expect; credentials are percent-encoded. */
  private static String postgresUrl(ProvisionedTenantDatabase database) {
    return "postgresql://"
        + URLEncoder.encode(database.roleName(), StandardCharsets.UTF_8)
        + ":"
        + URLEncoder.encode(database.password(), StandardCharsets.UTF_8)
        + "@"
        + database.host()
        + ":"
        + database.port()
        + "/"
        + database.databaseName();
  }

  private String randomSecret() {
    byte[] bytes = new byte[JWT_SECRET_BYTES];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
