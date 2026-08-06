package com.tenantos.registrar.services.aws;

/**
 * The outcome of provisioning a tenant's namespace: the name, plus enough cluster context to say
 * where it was applied. {@code initializeWorkspace} used to return the name alone, which meant a
 * tenant's record could not answer "on which cluster?" once there was more than one.
 *
 * <p>{@code clusterEndpoint} is only known to the live path, which learns it from DescribeCluster;
 * a stubbed apply leaves it null.
 */
public record ProvisionedNamespace(
    String namespace, String clusterName, String clusterEndpoint, String awsRegion) {}
