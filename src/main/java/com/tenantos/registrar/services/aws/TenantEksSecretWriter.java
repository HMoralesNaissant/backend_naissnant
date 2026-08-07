package com.tenantos.registrar.services.aws;

import com.tenantos.registrar.entity.Tenant;
import com.tenantos.registrar.entity.TenantNamespace;
import com.tenantos.registrar.repository.TenantNamespaceRepository;
import com.tenantos.registrar.services.database.ProvisionedTenantDatabase;
import com.tenantos.registrar.services.tenant.steps.records.ProvisionedAwsSecret;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes a tenant's database credential into their Kubernetes namespace as a Secret, so their
 * workloads can mount it without knowing anything about how it was provisioned.
 *
 * <p>This is the mirror, not the system of record - that is AWS Secrets Manager. Both are written
 * on every provisioning attempt, so they converge rather than being reconciled.
 *
 * <p>Applied with server-side apply, which makes republishing idempotent. That matters more here
 * than it looks: the step that calls this cannot be transactional (CREATE DATABASE forbids it), so
 * every external effect it has must tolerate being repeated.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TenantEksSecretWriter {

  private static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
  private static final String MANAGED_BY_VALUE = "tenantos-registrar";
  private static final String TENANT_SLUG_LABEL = "tenantos.io/tenant-slug";
  private static final String TENANT_ID_ANNOTATION = "tenantos.io/tenant-id";
  private static final String SECRET_TYPE = "Opaque";

  @Value("${tenant.database.secret.kubernetes-name:tenant-db-credentials}")
  private String secretName;

  @Value("${tenant.database.secret.kubernetes-enabled:true}")
  private boolean kubernetesSecretEnabled;

  private final KubernetesClientFactory kubernetesClientFactory;
  private final TenantNamespaceRepository tenantNamespaceRepository;

  /**
   * Writes (or overwrites) the credential Secret in the tenant's namespace and returns the name it
   * was written under.
   */
  public ProvisionedAwsSecret write(Tenant tenant, ProvisionedTenantDatabase database) {

    if (!kubernetesSecretEnabled) {
      return new ProvisionedAwsSecret(null, null);
    }
    String secretNamespace = null;
    // Failing loudly rather than deriving the name: an absent row means CREATE_NAMESPACE never
    // ran, which is an ordering bug rather than a retryable condition - the same stance
    // AbstractTransactionalStep.requireTenantId takes.
    TenantNamespace tenantNamespace =
        tenantNamespaceRepository
            .findById(tenant.getId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No tenant_namespace row for "
                            + tenant.getId()
                            + " - CREATE_NAMESPACE did not run"));
    secretNamespace = tenantNamespace.getNamespace();

    // stringData rather than data: Kubernetes base64-encodes it on the way in, so nothing here
    // needs to encode by hand - and a hand-rolled encode is the classic way to store a secret
    // that silently does not work.
    Map<String, String> values = new LinkedHashMap<>();
    values.put("host", database.host());
    values.put("port", String.valueOf(database.port()));
    values.put("database", database.databaseName());
    values.put("username", database.roleName());
    values.put("password", database.password());
    // Provided ready-made so every consumer does not re-derive it, and re-derive it differently.
    values.put("jdbc-url", database.jdbcUrl());

    Secret secret =
        new SecretBuilder()
            .withType(SECRET_TYPE)
            .withNewMetadata()
            .withName(secretName)
            .withNamespace(secretNamespace)
            .addToLabels(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
            .addToLabels(TENANT_SLUG_LABEL, tenant.getSlug())
            .addToAnnotations(TENANT_ID_ANNOTATION, String.valueOf(tenant.getId()))
            .endMetadata()
            .withStringData(values)
            .build();

    try (KubernetesClientFactory.AuthenticatedClient session = kubernetesClientFactory.open()) {
      session.client().resource(secret).inNamespace(secretNamespace).serverSideApply();
    }

    log.info(
        "Published database credential to secret {}/{} for tenant {}",
        secretNamespace,
        secretName,
        tenant.getId());
    return new ProvisionedAwsSecret(secretName, secretNamespace);
  }
}
