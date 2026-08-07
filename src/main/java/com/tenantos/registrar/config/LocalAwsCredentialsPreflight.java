package com.tenantos.registrar.config;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;

/**
 * Fails local startup fast on stale AWS credentials instead of letting the first AWS-backed request
 * (tenant provisioning, EKS auth, Secrets Manager) surface a confusing {@code ExpiredToken} error.
 *
 * <p>Checks the base SSO-backed profile first, shelling out to {@code aws sso login} to refresh it
 * if it's stale, then confirms the downstream {@code aws.local.assume-role-arn} AssumeRole (wired in
 * {@link AwsConfig#localAwsCredentialsProvider}) actually works. No-ops if that role isn't
 * configured, matching the zero-setup local dev path {@link AwsConfig} already supports.
 */
@Component
@Profile("local")
@Slf4j
@RequiredArgsConstructor
public class LocalAwsCredentialsPreflight implements ApplicationRunner {

  private static final Duration SSO_LOGIN_TIMEOUT = Duration.ofMinutes(5);

  private final AwsConfig awsConfig;
  private final AwsCredentialsProvider awsCredentialsProvider;
  private final Region awsRegion;

  @Value("${aws.profile:}")
  private String awsProfile;

  @Value("${aws.local.assume-role-arn:}")
  private String localAssumeRoleArn;

  @Override
  public void run(ApplicationArguments args) {
    if (localAssumeRoleArn == null || localAssumeRoleArn.isBlank()) {
      log.info("aws.local.assume-role-arn not set - skipping AWS credentials preflight check");
      return;
    }

    ensureBaseCredentialsValid();
    ensureAssumedRoleValid();
  }

  private void ensureBaseCredentialsValid() {
    AwsCredentialsProvider base = awsConfig.baseCredentialsProvider();
    try {
      base.resolveCredentials();
      log.info("AWS base credentials (profile '{}') are valid", awsProfile);
      return;
    } catch (SdkClientException e) {
      log.warn("AWS base credentials (profile '{}') are invalid/expired: {}", awsProfile, e.getMessage());
    }

    if (awsProfile == null || awsProfile.isBlank()) {
      throw new IllegalStateException(
          "AWS base credentials are invalid and no aws.profile is configured to refresh via "
              + "'aws sso login'. Set fresh AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY/AWS_SESSION_TOKEN "
              + "env vars, or configure aws.profile, then restart the app.");
    }

    log.info("Attempting 'aws sso login --profile {}' to refresh the session...", awsProfile);
    runAwsSsoLogin(awsProfile);

    try {
      base.resolveCredentials();
      log.info("AWS base credentials (profile '{}') refreshed successfully", awsProfile);
    } catch (SdkClientException e) {
      throw new IllegalStateException(
          "AWS base credentials for profile '"
              + awsProfile
              + "' are still invalid after 'aws sso login'. Check the profile in ~/.aws/config and "
              + "try logging in manually.",
          e);
    }
  }

  private void runAwsSsoLogin(String profile) {
    try {
      Process process =
          new ProcessBuilder("aws", "sso", "login", "--profile", profile).inheritIO().start();
      boolean finished = process.waitFor(SSO_LOGIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        throw new IllegalStateException(
            "'aws sso login --profile " + profile + "' timed out after " + SSO_LOGIN_TIMEOUT);
      }
      if (process.exitValue() != 0) {
        throw new IllegalStateException(
            "'aws sso login --profile " + profile + "' exited with code " + process.exitValue());
      }
    } catch (IOException e) {
      throw new IllegalStateException(
          "Could not invoke the 'aws' CLI - is AWS CLI v2 installed and on PATH?", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for 'aws sso login'", e);
    }
  }

  private void ensureAssumedRoleValid() {
    try {
      AwsCredentials assumed = awsCredentialsProvider.resolveCredentials();
      try (StsClient stsClient =
          StsClient.builder()
              .credentialsProvider(StaticCredentialsProvider.create(assumed))
              .region(awsRegion)
              .build()) {
        var identity = stsClient.getCallerIdentity();
        log.info("Assumed role {} successfully as {}", localAssumeRoleArn, identity.arn());
      }
    } catch (SdkException e) {
      throw new IllegalStateException(
          "Failed to assume role '" + localAssumeRoleArn + "' with profile '" + awsProfile + "': "
              + e.getMessage(),
          e);
    }
  }
}
