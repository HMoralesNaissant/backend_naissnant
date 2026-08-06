package com.tenantos.registrar.services.aws;

import org.springframework.stereotype.Service;

import com.tenantos.registrar.entity.Tenant;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Provisions the Kubernetes-side "workspace" for a newly created tenant: a dedicated namespace on
 * the EKS cluster, named after the tenant's slug. Authenticates per call via {@link
 * EksClusterAuthProvider} rather than holding a long-lived client, since the IAM-backed token is
 * only valid for a minute — cheap to re-fetch, not safe to cache.
 *
 * <p>The namespace name comes straight from {@code tenants.slug}, which is already normalized to a
 * valid RFC 1123 label and UNIQUE across tenants (see {@code TenantSlugGenerator}). This class used
 * to sanitize the free-text account name itself, which meant two tenants named "Acme" silently
 * targeted the same namespace.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EksNamespaceProvisioner {

  private static final String NAMESPACE_PREFIX = "tenant-";
  private static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
  private static final String MANAGED_BY_VALUE = "tenantos-registrar";
  private static final String TENANT_ID_ANNOTATION = "tenantos.io/tenant-id";
  private static final String TENANT_SLUG_LABEL = "tenantos.io/tenant-slug";
  private static final int MAX_NAMESPACE_LENGTH = 63;

  private final EksClusterAuthProvider eksClusterAuthProvider;

  /**
   * Creates (or updates, if already present) the tenant's namespace. Returns the name together with
   * the cluster it was applied to, so the caller can record where the workspace actually lives -
   * the name alone stops being enough the moment there is more than one cluster.
   *
   * <p>Idempotent via server-side apply, which is what makes it safe for the provisioning pipeline
   * to re-run this step after a worker dies mid-call.
   */
  public ProvisionedNamespace initializeWorkspace(Tenant tenant) {
    String namespaceName = namespaceNameFor(tenant.getSlug());
    EksClusterAuth auth = eksClusterAuthProvider.authenticate();

    Config config = new ConfigBuilder()
        .withMasterUrl(auth.endpoint())
        .withCaCertData(auth.certificateAuthorityData())
        .withOauthToken(auth.token())
        .build();

    try (KubernetesClient client = new KubernetesClientBuilder().withConfig(config).build()) {
      Namespace namespace = new NamespaceBuilder()
          .withNewMetadata()
            .withName(namespaceName)
            .addToLabels(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
            .addToLabels(TENANT_SLUG_LABEL, tenant.getSlug())
            .addToAnnotations(TENANT_ID_ANNOTATION, String.valueOf(tenant.getId()))
          .endMetadata()
          .build();

      client.resource(namespace).serverSideApply();
      log.info("Provisioned workspace namespace {} for tenant {}", namespaceName, tenant.getId());
    }

    return new ProvisionedNamespace(
        namespaceName,
        eksClusterAuthProvider.clusterName(),
        auth.endpoint(),
        eksClusterAuthProvider.awsRegion());
  }

  /**
   * The slug is already a valid DNS label, so this only has to add the prefix - and re-cap the
   * length, since a 63-character slug plus the prefix would overflow.
   *
   * <p>Public so a caller can derive the name a tenant's namespace will have without an EKS
   * round-trip. Keeping this the single definition of the naming rule is what stops a caller
   * inventing its own and drifting from what {@link #initializeWorkspace} actually applies.
   */
  public String namespaceNameFor(String slug) {
    String namespace = NAMESPACE_PREFIX + slug;
    return namespace.length() > MAX_NAMESPACE_LENGTH
        ? namespace.substring(0, MAX_NAMESPACE_LENGTH)
        : namespace;
  }
}
