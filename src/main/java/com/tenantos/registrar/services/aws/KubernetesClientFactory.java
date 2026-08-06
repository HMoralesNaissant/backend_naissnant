package com.tenantos.registrar.services.aws;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Builds a Kubernetes client authenticated against the EKS cluster.
 *
 * <p>Exists so the token-handling rule lives in exactly one place. The IAM-backed bearer token is
 * short-lived, so a client must be built per unit of work and closed - never cached in a field, and
 * never held across a slow call. With two callers each carrying their own copy of the construction,
 * that constraint was one careless edit away from being lost.
 *
 * <p>Callers own the returned client and must close it; it is {@link AutoCloseable}, so
 * try-with-resources is the intended usage.
 */
@Service
@RequiredArgsConstructor
public class KubernetesClientFactory {

  private final EksClusterAuthProvider eksClusterAuthProvider;

  /**
   * A client valid for the next few moments. Returned alongside the auth it was built from, since
   * callers routinely want the cluster endpoint for their own records.
   */
  public AuthenticatedClient open() {
    EksClusterAuth auth = eksClusterAuthProvider.authenticate();

    Config config =
        new ConfigBuilder()
            .withMasterUrl(auth.endpoint())
            .withCaCertData(auth.certificateAuthorityData())
            .withOauthToken(auth.token())
            .build();

    return new AuthenticatedClient(
        new KubernetesClientBuilder().withConfig(config).build(), auth);
  }

  /** A client and the cluster details it was authenticated against. */
  public record AuthenticatedClient(KubernetesClient client, EksClusterAuth auth)
      implements AutoCloseable {

    @Override
    public void close() {
      client.close();
    }
  }
}
