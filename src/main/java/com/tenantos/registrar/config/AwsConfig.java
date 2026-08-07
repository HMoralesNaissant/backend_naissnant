package com.tenantos.registrar.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

/**
 * Wires the AWS SDK v2 credentials the rest of the app injects as {@link AwsCredentialsProvider}.
 *
 * <p>Split by Spring profile rather than left to the default chain to pick implicitly: under {@code
 * local} the app explicitly assumes {@code aws.local.assume-role-arn} via STS, bootstrapped off the
 * named {@code aws.local.profile} (rather than an ambient {@code AWS_PROFILE} env var a plain IDE
 * run configuration may not set) if configured; every other profile (the deployed container) uses
 * the plain default chain, which resolves the EKS pod's projected service-account web identity token
 * (IRSA) or the ECS/EC2 instance profile with no extra code.
 *
 * <p>Region resolves the same way in both cases (AWS_REGION/AWS_DEFAULT_REGION env var, else
 * instance metadata), with `aws.region` as an explicit override. Falls back to {@link
 * #DEFAULT_REGION} when nothing resolves, so the app still starts in environments (local, CI) with
 * no AWS context at all.
 */
@Configuration
@Slf4j
public class AwsConfig {

  private static final Region DEFAULT_REGION = Region.US_WEST_2;
  private static final String LOCAL_ROLE_SESSION_NAME = "tenantos-registrar-local";

  @Value("${aws.region:}")
  private String configuredRegion;

  @Value("${aws.local.assume-role-arn:}")
  private String localAssumeRoleArn;

  @Value("${aws.profile:}")
  private String awsProfile;

  /**
   * Assumes {@code aws.local.assume-role-arn} via STS, bootstrapped off {@link
   * #baseCredentialsProvider()} - the named {@code aws.local.profile} if configured, otherwise
   * whatever base credentials (env vars, SSO session, default CLI profile) are ambient on the
   * developer's machine. Falls back to {@link #baseCredentialsProvider()} directly if the role ARN
   * isn't configured, so local dev that doesn't touch any AWS-backed feature keeps working with zero
   * setup.
   */
  @Bean
  @Profile("local")
  public AwsCredentialsProvider localAwsCredentialsProvider(Region awsRegion) {
    if (localAssumeRoleArn == null || localAssumeRoleArn.isBlank()) {
      log.info("aws.local.assume-role-arn not set - using the base credential chain locally");
      return baseCredentialsProvider();
    }

    log.info("Assuming role {} for local AWS access", localAssumeRoleArn);
    StsClient stsClient =
        StsClient.builder().credentialsProvider(baseCredentialsProvider()).region(awsRegion).build();
    return StsAssumeRoleCredentialsProvider.builder()
        .stsClient(stsClient)
        .refreshRequest(
            AssumeRoleRequest.builder()
                .roleArn(localAssumeRoleArn)
                .roleSessionName(LOCAL_ROLE_SESSION_NAME)
                .build())
        .build();
  }

  /**
   * The identity used both to sign the {@code AssumeRole} call and as the fallback when no role is
   * configured. {@code DefaultCredentialsProvider} alone only resolves the {@code [default]} profile
   * unless {@code AWS_PROFILE} happens to be set in the process's environment - {@code
   * aws.profile} makes a developer's named profile explicit instead of relying on that ambient
   * env var, which a plain {@code ./gradlew bootRun} or IDE run configuration may not set.
   */
  AwsCredentialsProvider baseCredentialsProvider() {
    if (awsProfile == null || awsProfile.isBlank()) {
      return DefaultCredentialsProvider.builder().build();
    }
    log.info("Using AWS profile {} for local base credentials", awsProfile);
    return ProfileCredentialsProvider.builder().profileName(awsProfile).build();
  }

  /** ECS task role / EKS IRSA - the default chain resolves both with no extra code. */
  @Bean
  @Profile("!local")
  public AwsCredentialsProvider containerAwsCredentialsProvider() {
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
      log.warn(
          "Could not resolve AWS region from environment/instance metadata, falling back to {}: {}",
          DEFAULT_REGION,
          e.getMessage());
      return DEFAULT_REGION;
    }
  }
}
