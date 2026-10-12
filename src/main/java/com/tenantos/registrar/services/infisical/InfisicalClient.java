package com.tenantos.registrar.services.infisical;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Minimal Infisical REST client: just the calls the provisioning step needs (folders and secrets),
 * authenticated with a machine identity via Universal Auth.
 *
 * <p>Everything here converges rather than assuming it runs once - the step that calls it is not
 * transactional, so a retry re-enters with whatever the previous attempt managed to write.
 */
@Service
@Slf4j
public class InfisicalClient {

  @Value("${infisical.api-url:}")
  private String apiUrl;

  @Value("${infisical.client-id:}")
  private String clientId;

  @Value("${infisical.client-secret:}")
  private String clientSecret;

  @Value("${infisical.project-id:}")
  private String projectId;

  /** Every call is applied to each of these, so all environments carry the same layout and keys. */
  @Value("${infisical.environments:dev,staging,prod}")
  private List<String> environments;

  /**
   * Creates {@code path} (an absolute path such as {@code /acme/backend}) and any missing parent
   * folders. Existing folders are left alone.
   */
  public void ensureFolder(String path) {
    RestClient http = authenticated();
    environments.forEach(environment -> ensureFolder(http, environment, path));
  }

  private void ensureFolder(RestClient http, String environment, String path) {
    StringBuilder parent = new StringBuilder();
    for (String name : path.split("/")) {
      if (name.isEmpty()) {
        continue;
      }
      String parentPath = parent.isEmpty() ? "/" : parent.toString();
      if (!folderNames(http, environment, parentPath).contains(name)) {
        http.post()
            .uri("/api/v1/folders")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                Map.of(
                    "workspaceId", projectId,
                    "environment", environment,
                    "name", name,
                    "path", parentPath))
            .retrieve()
            .toBodilessEntity();
        log.info(
            "Created Infisical folder {}/{} in {}",
            parentPath.equals("/") ? "" : parentPath,
            name,
            environment);
      }
      parent.append('/').append(name);
    }
  }

  /**
   * Creates each key in {@code path} that does not exist yet, with {@code placeholder} as its
   * value. Existing keys are never touched: the point of the placeholder is that a person fills it
   * in later, and a retry must not overwrite what they filled.
   *
   * @return the keys that were created
   */
  public List<String> createMissingSecrets(
      String path, Collection<String> keys, String placeholder) {
    RestClient http = authenticated();
    Set<String> created = new LinkedHashSet<>();
    for (String environment : environments) {
      Set<String> existing = secretKeys(http, environment, path);
      for (String key : keys) {
        if (existing.contains(key)) {
          continue;
        }
        http.post()
            .uri("/api/v3/secrets/raw/{key}", key)
            .contentType(MediaType.APPLICATION_JSON)
            .body(secretBody(environment, path, placeholder))
            .retrieve()
            .toBodilessEntity();
        created.add(key);
      }
    }
    return List.copyOf(created);
  }

  /**
   * Writes each entry to {@code path}, creating it or overwriting its value. For secrets this
   * service owns (unlike {@link #createMissingSecrets}, whose values a person fills in).
   */
  public void upsertSecrets(String path, Map<String, String> values) {
    RestClient http = authenticated();
    for (String environment : environments) {
      Set<String> existing = secretKeys(http, environment, path);
      values.forEach(
          (key, value) -> {
            RestClient.RequestBodySpec request =
                existing.contains(key)
                    ? http.patch().uri("/api/v3/secrets/raw/{key}", key)
                    : http.post().uri("/api/v3/secrets/raw/{key}", key);
            request
                .contentType(MediaType.APPLICATION_JSON)
                .body(secretBody(environment, path, value))
                .retrieve()
                .toBodilessEntity();
          });
    }
  }

  /**
   * Deletes the folder at {@code path} (and everything under it) in every environment. A folder
   * that is already gone is not an error.
   */
  public void deleteFolder(String path) {
    RestClient http = authenticated();
    String name = path.substring(path.lastIndexOf('/') + 1);
    String parent = path.substring(0, path.lastIndexOf('/'));
    for (String environment : environments) {
      try {
        http.method(org.springframework.http.HttpMethod.DELETE)
            .uri("/api/v1/folders/{name}", name)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                Map.of(
                    "workspaceId", projectId,
                    "environment", environment,
                    "path", parent.isEmpty() ? "/" : parent))
            .retrieve()
            .toBodilessEntity();
        log.info("Deleted Infisical folder {} in {}", path, environment);
      } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
        log.info("Infisical folder {} not present in {}", path, environment);
      }
    }
  }

  private Map<String, Object> secretBody(String environment, String path, String value) {
    return Map.of(
        "workspaceId", projectId,
        "environment", environment,
        "secretPath", path,
        "secretValue", value,
        "type", "shared");
  }

  private Set<String> folderNames(RestClient http, String environment, String parentPath) {
    Map<String, Object> body =
        http.get()
            .uri(
                "/api/v1/folders?workspaceId={p}&environment={e}&path={path}",
                projectId,
                environment,
                parentPath)
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});
    return namesOf(body, "folders", "name");
  }

  private Set<String> secretKeys(RestClient http, String environment, String path) {
    Map<String, Object> body =
        http.get()
            .uri(
                "/api/v3/secrets/raw?workspaceId={p}&environment={e}&secretPath={path}",
                projectId,
                environment,
                path)
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});
    return namesOf(body, "secrets", "secretKey");
  }

  @SuppressWarnings("unchecked")
  private static Set<String> namesOf(Map<String, Object> body, String listField, String field) {
    if (body == null || !(body.get(listField) instanceof List<?> items)) {
      return Set.of();
    }
    return items.stream()
        .map(item -> ((Map<String, Object>) item).get(field))
        .map(String::valueOf)
        .collect(Collectors.toSet());
  }

  /** A client carrying a fresh access token; tokens are short-lived and calls here are rare. */
  private RestClient authenticated() {
    RestClient anonymous = RestClient.builder().baseUrl(apiUrl).build();
    Map<String, Object> login =
        anonymous
            .post()
            .uri("/api/v1/auth/universal-auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("clientId", clientId, "clientSecret", clientSecret))
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});
    if (login == null || login.get("accessToken") == null) {
      throw new IllegalStateException("Infisical login returned no accessToken");
    }
    return RestClient.builder()
        .baseUrl(apiUrl)
        .defaultHeader("Authorization", "Bearer " + login.get("accessToken"))
        .build();
  }
}
