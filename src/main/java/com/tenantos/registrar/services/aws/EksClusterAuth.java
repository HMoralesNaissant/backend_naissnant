package com.tenantos.registrar.services.aws;

/**
 * Where an EKS cluster's Kubernetes API server lives and how to trust it: the endpoint, plus its
 * base64 certificate authority data for TLS.
 *
 * <p>No token here, deliberately. A token is signed per request by
 * {@link EksClusterAuthProvider#generateToken()} rather than handed over once at client-build time -
 * EKS caps a token's signed lifetime at 15 minutes, so anything cached alongside the endpoint would
 * be a deadline waiting to be hit.
 */
public record EksClusterAuth(String endpoint, String certificateAuthorityData) {}
