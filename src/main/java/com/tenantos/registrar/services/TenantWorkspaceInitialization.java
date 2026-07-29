package com.tenantos.registrar.services;

import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.services.aws.EksClusterAuth;
import com.tenantos.registrar.services.aws.EksClusterAuthProvider;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Provisions the Kubernetes-side "workspace" for a newly registered tenant: a dedicated namespace
 * on the EKS cluster, named after the tenant's account name. Authenticates per call via {@link
 * EksClusterAuthProvider} rather than holding a long-lived client, since the IAM-backed token is
 * only valid for a minute — cheap to re-fetch, not safe to cache.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TenantWorkspaceInitialization {

  private static final String NAMESPACE_PREFIX = "tenant-";
  private static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
  private static final String MANAGED_BY_VALUE = "tenantos-registrar";
  private static final String COMPANY_EMAIL_ANNOTATION = "tenantos.io/company-email";
  private static final Pattern NON_LABEL_CHARS = Pattern.compile("[^a-z0-9-]+");
  private static final Pattern LEADING_TRAILING_HYPHENS = Pattern.compile("^-+|-+$");
  private static final int MAX_NAMESPACE_LENGTH = 63;

  private final EksClusterAuthProvider eksClusterAuthProvider;

  /**
   * Creates (or updates, if already present) the tenant's namespace. Returns the namespace name
   * so the caller can persist/log it.
   */
  public String initializeWorkspace(TenantsRegistration registration) {
    String namespaceName = namespaceNameFor(registration);
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
            .addToAnnotations(COMPANY_EMAIL_ANNOTATION, registration.getCompanyEmail())
          .endMetadata()
          .build();

      client.resource(namespace).serverSideApply();
      log.info("Provisioned workspace namespace {} for {}", namespaceName, registration.getCompanyEmail());
    }

    return namespaceName;
  }

  /**
   * Kubernetes namespace names must be a valid RFC 1123 DNS label (lowercase alphanumerics and
   * hyphens, <= 63 chars); the account name is user-supplied free text, so it's sanitized down
   * to that alphabet, falling back to a hash of the company email if nothing usable survives.
   */
  private String namespaceNameFor(TenantsRegistration registration) {
    String base = registration.getAccountName();
    String sanitized = base == null
        ? ""
        : LEADING_TRAILING_HYPHENS
            .matcher(NON_LABEL_CHARS.matcher(base.toLowerCase(Locale.ROOT)).replaceAll("-"))
            .replaceAll("");

    if (sanitized.isBlank()) {
      sanitized = Integer.toHexString(registration.getCompanyEmail().hashCode());
    }

    String namespace = NAMESPACE_PREFIX + sanitized;
    return namespace.length() > MAX_NAMESPACE_LENGTH
        ? namespace.substring(0, MAX_NAMESPACE_LENGTH)
        : namespace;
  }
}
