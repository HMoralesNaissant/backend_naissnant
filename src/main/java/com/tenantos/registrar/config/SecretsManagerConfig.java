package com.tenantos.registrar.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Client for the store of record for per-tenant database credentials. Reuses the credential
 * provider and region from {@link AwsConfig}, so it authenticates the same way everything else does
 * (env vars locally, IRSA in the cluster).
 *
 * <p>Conditional on the feature being switched on, so environments without AWS - local development
 * in particular - do not pay for a client they will never call. The publisher that consumes this is
 * conditional on the same property, so nothing is left holding an absent bean.
 */
@Configuration
@ConditionalOnProperty(
    value = "tenant.database.secret.aws-secrets-manager-enabled",
    havingValue = "true")
public class SecretsManagerConfig {

  @Bean
  public SecretsManagerClient secretsManagerClient(
      AwsCredentialsProvider awsCredentialsProvider, Region awsRegion) {
    return SecretsManagerClient.builder()
        .credentialsProvider(awsCredentialsProvider)
        .region(awsRegion)
        .build();
  }
}
