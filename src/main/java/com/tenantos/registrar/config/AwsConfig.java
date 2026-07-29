package com.tenantos.registrar.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

/**
 * Wires the AWS SDK v2 default credential chain: static AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY
 * env vars locally, and the EKS pod's projected service-account web identity token
 * (AWS_WEB_IDENTITY_TOKEN_FILE/AWS_ROLE_ARN, i.e. IRSA) or the EC2/ECS instance profile in
 * deployed environments — no code branching needed between the two, the chain tries each in turn.
 * Region resolves the same way (AWS_REGION/AWS_DEFAULT_REGION env var, else instance metadata),
 * with `aws.region` as an explicit override. Falls back to {@link #DEFAULT_REGION} when nothing
 * resolves, so the app still starts in environments (local, CI) with no AWS context at all.
 */
@Configuration
@Slf4j
public class AwsConfig {

  private static final Region DEFAULT_REGION = Region.US_WEST_2;

  @Value("${aws.region:}")
  private String configuredRegion;

  @Bean
  public AwsCredentialsProvider awsCredentialsProvider() {
    return DefaultCredentialsProvider.builder().build();
  }

  @Bean
  public Region awsRegion() {
    if (configuredRegion != null && !configuredRegion.isBlank()) {
      return Region.of(configuredRegion);
    }
    try {
      return DefaultAwsRegionProviderChain.builder().build().getRegion();
    } catch (SdkClientException e) {
      log.warn("Could not resolve AWS region from environment/instance metadata, falling back to {}: {}",
          DEFAULT_REGION, e.getMessage());
      return DEFAULT_REGION;
    }
  }
}
