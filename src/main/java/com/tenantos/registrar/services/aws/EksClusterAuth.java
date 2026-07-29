package com.tenantos.registrar.services.aws;

/**
 * Everything needed to talk to an EKS cluster's Kubernetes API server: the endpoint, its base64
 * certificate authority data (for TLS trust), and a short-lived IAM-backed bearer token
 * (aws-iam-authenticator's "k8s-aws-v1." scheme) to authenticate the request.
 */
public record EksClusterAuth(String endpoint, String certificateAuthorityData, String token) {}
