package com.tenantos.registrar.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eks.EksClient;

/**
 * EksClient reuses the credential provider and region wired in {@link AwsConfig}, so it
 * authenticates the same way (env vars locally, IRSA/instance profile in the cluster).
 */
@Configuration
public class EksConfig {

  @Bean
  public EksClient eksClient(AwsCredentialsProvider awsCredentialsProvider, Region awsRegion) {
    return EksClient.builder()
        .credentialsProvider(awsCredentialsProvider)
        .region(awsRegion)
        .build();
  }
}
