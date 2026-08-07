package com.tenantos.registrar.services.aws;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.Cluster;
import software.amazon.awssdk.services.eks.model.DescribeClusterRequest;

/**
 * Authenticates against an EKS cluster's Kubernetes API server the same way `aws eks get-token`
 * does: a presigned STS GetCallerIdentity URL, carrying an `x-k8s-aws-id` header naming the
 * cluster, base64url-encoded with a "k8s-aws-v1." prefix. The cluster's own IAM-mapped
 * `aws-auth`/access-entry config is what actually authorizes this identity — this class only
 * produces the token, not the RBAC binding.
 *
 * <p>Endpoint/CA are fetched once per process and cached, since they don't change. The token is
 * never cached: {@link #generateToken()} is handed to the Kubernetes client as a token
 * <em>provider</em>, so one is signed afresh for every request. See {@link #TOKEN_TTL} for the
 * server-side ceiling that makes a long-lived token impossible in the first place.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EksClusterAuthProvider {

  private static final String TOKEN_PREFIX = "k8s-aws-v1.";
  private static final String CLUSTER_ID_HEADER = "x-k8s-aws-id";
  private static final String STS_SIGNING_NAME = "sts";
  private static final String STS_GET_CALLER_IDENTITY_PATH = "/?Action=GetCallerIdentity&Version=2011-06-15";

  /**
   * How long the presigned URL is signed for, and <b>there is a hard ceiling of 900 seconds</b>. EKS
   * rejects any token whose {@code X-Amz-Expires} exceeds that, and it rejects it as a 401 before it
   * ever identifies the caller - which reads exactly like a missing access entry and is not. Measured
   * against a live cluster: 900 returns 200, 901 returns 401.
   *
   * <p>60 matches what {@code aws eks get-token} emits. Nothing is gained by going higher, because a
   * fresh token is signed per request (see {@link #generateToken()}) rather than being held for the
   * life of a client.
   */
  private static final Duration TOKEN_TTL = Duration.ofSeconds(60);

  private final EksClient eksClient;
  private final AwsCredentialsProvider awsCredentialsProvider;
  private final Region awsRegion;

  @Value("${aws.eks.cluster-name:}")
  private String clusterName;

  private volatile Cluster cachedCluster;

  public EksClusterAuth authenticate() {
    if (clusterName == null || clusterName.isBlank()) {
      throw new IllegalStateException("aws.eks.cluster-name (EKS_CLUSTER_NAME) is not configured");
    }
    Cluster cluster = describeCluster();
    return new EksClusterAuth(cluster.endpoint(), cluster.certificateAuthority().data());
  }

  /**
   * The configured cluster's name, or null if none is configured. Deliberately reads the property
   * rather than going through {@link #describeCluster()}: callers that only want to record which
   * cluster they targeted should not be forced into an AWS round-trip, or fail outright in
   * environments with no EKS configured at all.
   */
  public String clusterName() {
    return clusterName == null || clusterName.isBlank() ? null : clusterName;
  }

  /** The region every AWS call here is signed for. Same no-round-trip contract as {@link #clusterName()}. */
  public String awsRegion() {
    return awsRegion.id();
  }

  private Cluster describeCluster() {
    Cluster cluster = cachedCluster;
    if (cluster == null) {
      synchronized (this) {
        cluster = cachedCluster;
        if (cluster == null) {
          log.info("Describing EKS cluster {}", clusterName);
          cluster = eksClient
              .describeCluster(DescribeClusterRequest.builder().name(clusterName).build())
              .cluster();
          cachedCluster = cluster;
        }
      }
    }
    return cluster;
  }

  /**
   * A bearer token valid for the next {@link #TOKEN_TTL}. Public because it is wired in as fabric8's
   * {@code OAuthTokenProvider}, which calls it on every single request - so a client never carries a
   * token older than the request it is attached to, and expiry stops being something to manage.
   *
   * <p>Cheap enough to call per request: presigning is HMAC arithmetic with no network round-trip,
   * and {@code resolveCredentials()} is the AWS SDK's own per-call path, cached and refreshed by the
   * provider chain. Deliberately not memoized - a cache would reintroduce the staleness window that
   * signing per request exists to remove.
   */
  public String generateToken() {
    if (clusterName == null || clusterName.isBlank()) {
      throw new IllegalStateException("aws.eks.cluster-name (EKS_CLUSTER_NAME) is not configured");
    }
    SdkHttpFullRequest request = SdkHttpFullRequest.builder()
        .method(SdkHttpMethod.GET)
        .uri(URI.create("https://sts." + awsRegion.id() + ".amazonaws.com" + STS_GET_CALLER_IDENTITY_PATH))
        .putHeader(CLUSTER_ID_HEADER, clusterName)
        .build();

    SignedRequest signedRequest = AwsV4HttpSigner.create().sign(r -> r
        .identity(awsCredentialsProvider.resolveCredentials())
        .request(request)
        .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, STS_SIGNING_NAME)
        .putProperty(AwsV4HttpSigner.REGION_NAME, awsRegion.id())
        .putProperty(AwsV4FamilyHttpSigner.AUTH_LOCATION, AwsV4FamilyHttpSigner.AuthLocation.QUERY_STRING)
        .putProperty(AwsV4FamilyHttpSigner.EXPIRATION_DURATION, TOKEN_TTL));

    String encoded = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(signedRequest.request().getUri().toString().getBytes(StandardCharsets.UTF_8));
    return TOKEN_PREFIX + encoded;
  }
}
