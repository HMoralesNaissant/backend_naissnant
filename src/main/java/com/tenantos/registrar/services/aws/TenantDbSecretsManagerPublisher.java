package com.tenantos.registrar.services.aws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.tenantos.registrar.entity.Tenant;
import com.tenantos.registrar.services.database.ProvisionedTenantDatabase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.ResourceExistsException;
import software.amazon.awssdk.services.secretsmanager.model.Tag;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Publishes a tenant's database credential to AWS Secrets Manager, the system of record for it.
 * The Kubernetes Secret written by {@link TenantEksSecretWriter} is a mirror of this.
 *
 * <p>The stored document deliberately uses the same field names as an RDS-managed secret
 * ({@code host}, {@code port}, {@code dbname}, {@code username}, {@code password},
 * {@code engine}), so managed rotation or anything else expecting that shape can consume it later
 * without a translation layer.
 *
 * <p><b>Convergence.</b> Secrets Manager has no upsert, so this creates and falls back to putting a
 * new version when the secret already exists. That is what makes it safe for the provisioning step
 * to re-run after a crash - each attempt overwrites with a freshly minted password.
 *
 * <p><b>Two operational notes.</b> A secret <i>scheduled for deletion</i> still occupies its name
 * and will reject creation until it is restored ({@code RestoreSecret}) or its deletion completes -
 * so deleting a tenant and re-creating them inside the recovery window needs manual intervention.
 * And the IRSA role this runs under needs {@code secretsmanager:CreateSecret},
 * {@code PutSecretValue}, {@code DescribeSecret} and {@code TagResource}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
    value = "tenant.database.secret.aws-secrets-manager-enabled",
    havingValue = "true")
public class TenantDbSecretsManagerPublisher {

  private static final String ENGINE = "postgres";

  @Value("${tenant.database.secret.name-prefix:tenantos/tenant}")
  private String namePrefix;

  private final SecretsManagerClient secretsManagerClient;
  private final ObjectMapper objectMapper;

  /** Publishes the credential and returns the secret's ARN. */
  public String publish(Tenant tenant, ProvisionedTenantDatabase database) {
    String secretId = secretNameFor(tenant.getSlug());
    String payload = toJson(database);

    try {
      String arn =
          secretsManagerClient
              .createSecret(
                  CreateSecretRequest.builder()
                      .name(secretId)
                      .secretString(payload)
                      .description("Database credential for tenant " + tenant.getSlug())
                      .tags(
                          Tag.builder()
                              .key("tenantos.io/tenant-id")
                              .value(String.valueOf(tenant.getId()))
                              .build(),
                          Tag.builder()
                              .key("tenantos.io/tenant-slug")
                              .value(tenant.getSlug())
                              .build(),
                          Tag.builder()
                              .key("app.kubernetes.io/managed-by")
                              .value("tenantos-registrar")
                              .build())
                      .build())
              .arn();
      log.info("Created Secrets Manager secret {} for tenant {}", secretId, tenant.getId());
      return arn;

    } catch (ResourceExistsException e) {
      // A previous attempt got this far. Overwrite rather than fail: the password in hand is the
      // one the database now accepts, so the stored value is the stale one.
      String arn =
          secretsManagerClient
              .putSecretValue(
                  PutSecretValueRequest.builder().secretId(secretId).secretString(payload).build())
              .arn();
      log.info("Rotated Secrets Manager secret {} for tenant {}", secretId, tenant.getId());
      return arn;
    }
  }

  /** The secret's name. Public so callers can reference it without a round-trip. */
  public String secretNameFor(String slug) {
    return namePrefix + "/" + slug + "/db";
  }

  private String toJson(ProvisionedTenantDatabase database) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("engine", ENGINE);
    node.put("host", database.host());
    node.put("port", database.port());
    node.put("dbname", database.databaseName());
    node.put("username", database.roleName());
    node.put("password", database.password());
    node.put("jdbcUrl", database.jdbcUrl());
    try {
      return objectMapper.writeValueAsString(node);
    } catch (JacksonException e) {
      // Not reachable for a flat ObjectNode of strings and ints, but the message must never carry
      // the node itself - it holds the password.
      throw new IllegalStateException("Could not serialise the tenant database secret", e);
    }
  }
}
