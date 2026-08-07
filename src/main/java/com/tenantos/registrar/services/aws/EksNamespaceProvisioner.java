package com.tenantos.registrar.services.aws;

import org.springframework.stereotype.Service;

import com.tenantos.registrar.entity.Tenant;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.HttpURLConnection;

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
  private final KubernetesClientFactory kubernetesClientFactory;

  /**
   * Creates the tenant's namespace if it does not already exist. Returns the name together with the
   * cluster it was applied to, so the caller can record where the workspace actually lives - the
   * name alone stops being enough the moment there is more than one cluster.
   *
   * <p>Idempotent by looking before creating, which is what makes it safe for the provisioning
   * pipeline to re-run this step after a worker dies mid-call.
   *
   * <p><b>An existing namespace is left exactly as found.</b> This used to server-side apply
   * unconditionally, which also corrected drift by re-stamping the labels every run; check-then-
   * create does not. That is the deliberate trade for not issuing a write against a namespace that
   * is already correct - if label drift ever needs repairing, it wants to be its own reconciler,
   * not a side effect of provisioning.
   */
  public ProvisionedNamespace initializeWorkspace(Tenant tenant) {
    String namespaceName = namespaceNameFor(tenant.getSlug());

    try (KubernetesClientFactory.AuthenticatedClient session = kubernetesClientFactory.open()) {
      Namespace existing = session.client().namespaces().withName(namespaceName).get();

      if (existing != null) {
        requireNotAnotherTenants(existing, namespaceName, tenant);
        log.info(
            "Namespace {} already exists for tenant {}, leaving it as is",
            namespaceName,
            tenant.getId());
      } else {
        create(session, namespaceName, tenant);
      }

      return new ProvisionedNamespace(
          namespaceName,
          eksClusterAuthProvider.clusterName(),
          session.auth().endpoint(),
          eksClusterAuthProvider.awsRegion());
    }
  }

  private void create(
      KubernetesClientFactory.AuthenticatedClient session, String namespaceName, Tenant tenant) {
    Namespace namespace =
        new NamespaceBuilder()
            .withNewMetadata()
            .withName(namespaceName)
            .addToLabels(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
            .addToLabels(TENANT_SLUG_LABEL, tenant.getSlug())
            .addToAnnotations(TENANT_ID_ANNOTATION, String.valueOf(tenant.getId()))
            .endMetadata()
            .build();

    try {
      session.client().resource(namespace).create();
      log.info("Provisioned workspace namespace {} for tenant {}", namespaceName, tenant.getId());
    } catch (KubernetesClientException e) {
      // Another worker won the race between the lookup above and this call. The namespace exists,
      // which is all this step promised - treating it as failure would dead-letter a job whose work
      // is already done.
      if (e.getCode() != HttpURLConnection.HTTP_CONFLICT) {
        throw e;
      }
      log.info(
          "Namespace {} was created concurrently for tenant {}", namespaceName, tenant.getId());
    }
  }

  /**
   * Refuses to hand back a namespace that is annotated for a different tenant. Slugs are UNIQUE, so
   * this should be unreachable - but it is reachable via the 63-character truncation in {@link
   * #namespaceNameFor}, where two long slugs can collide, and two tenants quietly sharing one
   * namespace is a worse outcome than a job that stops and says so.
   *
   * <p>A namespace with no such annotation is adopted with a warning: one created by hand is a
   * plausible thing to find, and refusing it would wedge the pipeline with no way forward.
   */
  private void requireNotAnotherTenants(Namespace existing, String namespaceName, Tenant tenant) {
    String owner =
        existing.getMetadata().getAnnotations() == null
            ? null
            : existing.getMetadata().getAnnotations().get(TENANT_ID_ANNOTATION);

    if (owner == null || owner.isBlank()) {
      log.warn(
          "Namespace {} exists but carries no {} annotation - adopting it for tenant {}",
          namespaceName,
          TENANT_ID_ANNOTATION,
          tenant.getId());
      return;
    }
    if (!owner.equals(String.valueOf(tenant.getId()))) {
      throw new IllegalStateException(
          "Namespace "
              + namespaceName
              + " already belongs to tenant "
              + owner
              + ", refusing to reuse it for tenant "
              + tenant.getId());
    }
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
