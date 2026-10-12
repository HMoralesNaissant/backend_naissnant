package com.tenantos.registrar.services.deploy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Fires a Forgejo Actions {@code workflow_dispatch}. The one call the tenant deploy needs, so this
 * is a thin RestClient wrapper in the same spirit as {@code InfisicalClient} rather than a client
 * library.
 *
 * <p>Fire-and-forget by design: the dispatch endpoint does not reliably hand back a run id across
 * Forgejo versions, so nothing here tracks the run. Whether the deploy worked is decided by looking
 * at the cluster ({@link TenantAppDeployer#awaitReady}), which is the authoritative answer anyway.
 */
@Service
@Slf4j
public class ForgejoWorkflowClient {

  @Value("${tenant.deploy.forgejo.api-url:}")
  private String apiUrl;

  @Value("${tenant.deploy.forgejo.token:}")
  private String token;

  /** {@code owner/name} of the repository holding the workflow. */
  @Value("${tenant.deploy.forgejo.repository:}")
  private String repository;

  @Value("${tenant.deploy.forgejo.workflow:tenant-deploy_v1.yml}")
  private String workflow;

  @Value("${tenant.deploy.forgejo.ref:main}")
  private String ref;

  public void dispatch(Map<String, String> inputs) {
    String[] parts = repository.split("/", 2);
    if (apiUrl.isBlank() || token.isBlank() || parts.length != 2) {
      throw new IllegalStateException(
          "tenant.deploy.forgejo.api-url, token and repository (owner/name) must be set");
    }

    RestClient.builder()
        .baseUrl(apiUrl)
        .defaultHeader("Authorization", "token " + token)
        .build()
        .post()
        .uri(
            "/api/v1/repos/{owner}/{name}/actions/workflows/{workflow}/dispatches",
            parts[0],
            parts[1],
            workflow)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("ref", ref, "inputs", inputs))
        .retrieve()
        .toBodilessEntity();

    log.info("Dispatched {} in {} for {}", workflow, repository, inputs.get("app_name"));
  }
}
