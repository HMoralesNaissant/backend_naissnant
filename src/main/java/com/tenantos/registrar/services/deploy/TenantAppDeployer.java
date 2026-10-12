package com.tenantos.registrar.services.deploy;

import com.tenantos.registrar.entity.Tenant;
import com.tenantos.registrar.services.kubernetes.KubernetesClientFactory;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deploys a tenant's backend and UI by dispatching the {@code tenant-deploy} workflow (which runs
 * skaffolder + skaffold with the platform's existing templates), then confirms the result against
 * the cluster.
 *
 * <p>Both halves tolerate being repeated, which the pipeline relies on: the workflow applies with
 * skaffold (idempotent) and serialises per tenant and app, so a duplicate dispatch after a crash
 * costs a redundant rollout and nothing more.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TenantAppDeployer {

  public static final String BACKEND = "backend";
  public static final String UI = "ui";

  // The name ends up as a Service/Ingress host label ("<name>"), which is capped at 63, so
  // the slug part leaves room for the longest suffix ("-backend").
  private static final int MAX_SLUG_PART = 40;

  @Value("${tenant.deployment.environment:prod}")
  private String environment;

  @Value("${tenant.deployment.backend-image:}")
  private String backendImage;

  @Value("${tenant.deployment.ui-image:}")
  private String uiImage;

  @Value("${tenant.deployment.await-timeout-seconds:240}")
  private long awaitTimeoutSeconds;

  @Value("${tenant.deployment.poll-interval-seconds:5}")
  private long pollIntervalSeconds;

  private final ForgejoWorkflowClient forgejo;
  private final KubernetesClientFactory kubernetesClientFactory;

  /**
   * Resource name for one of the tenant's apps. Defined here once - the workflow is handed these
   * names rather than deriving its own, so the two cannot drift. Slugs can be 63 characters, so a
   * long one is shortened and given a hash of the full slug, which keeps two long slugs sharing a
   * prefix from colliding.
   */
  public String appNameFor(String slug, String app) {
    String part = slug;
    if (slug.length() > MAX_SLUG_PART) {
      part = slug.substring(0, MAX_SLUG_PART - 5) + "-" + shortHash(slug);
    }
    return part + "-" + app;
  }

  /** Asks CI to deploy both apps into the tenant's namespace. */
  public void requestDeploy(Tenant tenant, String namespace) {
    // Both checked before either dispatch: failing on the second would leave the first already
    // running for a tenant the pipeline is about to retry.
    requireImage(BACKEND, backendImage);
    requireImage(UI, uiImage);

    String backendName = appNameFor(tenant.getSlug(), BACKEND);
    String uiName = appNameFor(tenant.getSlug(), UI);

    dispatchApp(tenant, namespace, BACKEND, backendName, backendName, uiName, backendImage);
    dispatchApp(tenant, namespace, UI, uiName, backendName, uiName, uiImage);
  }

  private void dispatchApp(
      Tenant tenant,
      String namespace,
      String app,
      String appName,
      String backendName,
      String uiName,
      String image) {
    Map<String, String> inputs = new LinkedHashMap<>();
    inputs.put("env", environment);
    inputs.put("tenant_slug", tenant.getSlug());
    inputs.put("namespace", namespace);
    inputs.put("app", app);
    inputs.put("app_name", appName);
    // Each app needs the other's name: the UI points at the backend, the backend allows the UI.
    inputs.put("backend_name", backendName);
    inputs.put("ui_name", uiName);
    inputs.put("image", image);
    forgejo.dispatch(inputs);
  }

  private static void requireImage(String app, String image) {
    if (image.isBlank()) {
      throw new IllegalStateException("tenant.deployment." + app + "-image is not set");
    }
  }

  /**
   * Blocks until both Deployments report an available replica, or throws once the timeout passes.
   * Bounded well inside the provisioning lease so a worker is never mistaken for dead while it
   * waits; a timeout throws, and the pipeline's own backoff re-enters here.
   */
  public void awaitReady(Tenant tenant, String namespace) {
    List<String> names =
        List.of(appNameFor(tenant.getSlug(), BACKEND), appNameFor(tenant.getSlug(), UI));
    Instant deadline = Instant.now().plus(Duration.ofSeconds(awaitTimeoutSeconds));

    List<String> notReady;
    do {
      notReady = notReady(namespace, names);
      if (notReady.isEmpty()) {
        return;
      }
      sleep();
    } while (Instant.now().isBefore(deadline));

    throw new IllegalStateException(
        "Deployments not ready in "
            + namespace
            + " after "
            + awaitTimeoutSeconds
            + "s: "
            + notReady
            + " - check the tenant-deploy workflow run");
  }

  private List<String> notReady(String namespace, List<String> names) {
    List<String> pending = new ArrayList<>();
    try (KubernetesClient client = kubernetesClientFactory.open()) {
      for (String name : names) {
        Deployment deployment = client.apps().deployments().inNamespace(namespace).withName(name).get();
        if (!isAvailable(deployment)) {
          pending.add(name);
        }
      }
    }
    return pending;
  }

  /**
   * Available means a replica is serving <i>this</i> generation: without the observedGeneration
   * check, a Deployment still reporting the previous spec's replicas would read as ready the moment
   * a redeploy was applied.
   */
  private static boolean isAvailable(Deployment deployment) {
    if (deployment == null || deployment.getStatus() == null) {
      return false;
    }
    Long generation = deployment.getMetadata().getGeneration();
    Long observed = deployment.getStatus().getObservedGeneration();
    Integer available = deployment.getStatus().getAvailableReplicas();
    return available != null
        && available >= 1
        && (generation == null || (observed != null && observed >= generation));
  }

  private void sleep() {
    try {
      Thread.sleep(Duration.ofSeconds(pollIntervalSeconds));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for tenant deployments", e);
    }
  }

  private static String shortHash(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (int i = 0; i < 2; i++) {
        hex.append(String.format("%02x", digest[i]));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
