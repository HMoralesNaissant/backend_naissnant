package com.tenantos.registrar.services.kubernetes;

import org.springframework.stereotype.Service;

import io.fabric8.kubernetes.api.model.NamedContext;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds Kubernetes clients using fabric8's own auto-configuration: {@code KUBECONFIG} env var,
 * else {@code ~/.kube/config}, else the in-cluster service account token when running as a pod -
 * the same discovery {@code kubectl} uses.
 */
@Service
@Slf4j
public class KubernetesClientFactory {

  private volatile Config cachedConfig;

  public KubernetesClient open() {
    return new KubernetesClientBuilder().withConfig(config()).build();
  }

  public String clusterEndpoint() {
    return config().getMasterUrl();
  }

  public String clusterName() {
    NamedContext context = config().getCurrentContext();
    return context == null ? null : context.getContext().getCluster();
  }

  private Config config() {
    Config config = cachedConfig;
    if (config == null) {
      synchronized (this) {
        config = cachedConfig;
        if (config == null) {
          config = Config.autoConfigure(null);
          cachedConfig = config;
        }
      }
    }
    return config;
  }
}
