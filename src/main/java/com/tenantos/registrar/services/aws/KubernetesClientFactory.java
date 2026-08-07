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
 * <p>Exists so the token-handling rule lives in exactly one place. That rule is now enforced by
 * construction rather than by discipline: the client is given a token <em>provider</em>, not a
 * token, and fabric8 calls it on every request (and again on any 401, then retries). A client
 * therefore cannot outlive its credential no matter how long it is held, which is what the
 * IAM-backed token's 15-minute server-side cap otherwise guarantees it would.
 *
 * <p>{@code open()} still builds one client per unit of work, since nothing here needs otherwise and
 * it keeps lifetimes obvious - but a longer-lived or singleton client is now safe, which it was not
 * when the token was baked in as a fixed string.
 *
 * <p>Callers own the returned client and must close it; it is {@link AutoCloseable}, so
 * try-with-resources is the intended usage.
 */
@Service
@RequiredArgsConstructor
public class KubernetesClientFactory {

  private final EksClusterAuthProvider eksClusterAuthProvider;

  /**
   * An authenticated client. Returned alongside the cluster details it was built from, since callers
   * routinely want the endpoint for their own records.
   */
  public AuthenticatedClient open() {
    EksClusterAuth auth = eksClusterAuthProvider.authenticate();

    Config config =
        new ConfigBuilder()
            .withMasterUrl(auth.endpoint())
            .withCaCertData(auth.certificateAuthorityData())
            // A provider, not a token: fabric8 invokes this per request, so the Authorization header
            // always carries a signature minted moments earlier.
            .withOauthTokenProvider(eksClusterAuthProvider::generateToken)
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
