-- Flyway migration V6: record where a tenant's Kubernetes workspace actually lives
--
-- Until now the only trace of a provisioned namespace was tenant_workspace_provisioning.namespace,
-- a column on the queue record. That conflates two lifetimes: the job row exists to be claimed,
-- retried and eventually forgotten, while "this tenant's workspace is namespace X on cluster Y" is
-- a durable fact about the tenant. It also recorded no cluster at all, so the moment there is more
-- than one EKS cluster - or one gets replaced - an older tenant's row cannot say where its
-- workspace is.

CREATE TABLE tenant_namespace
(
    -- Keyed on tenant_id rather than a surrogate id, the same choice tenant_settings made in V3:
    -- it is 1:1 with the tenant, which is also what makes CREATE_NAMESPACE idempotent for free -
    -- a retry upserts this row instead of appending a second one.
    tenant_id        UUID PRIMARY KEY REFERENCES tenants (id) ON DELETE CASCADE,

    -- UNIQUE for the same reason tenants.slug is (the name is "tenant-<slug>"): two tenants sharing
    -- one namespace is a cross-tenant data leak, not a duplicate row, so it is worth a constraint
    -- rather than a convention. 63 chars is the RFC 1123 DNS label limit Kubernetes enforces.
    namespace        VARCHAR(63) NOT NULL UNIQUE,

    -- Nullable: which cluster a namespace is on is only known when the real EKS call runs. The step
    -- is currently stubbed, and locally aws.eks.cluster-name is empty, so these stay null there.
    -- cluster_endpoint in particular is only ever populated by the live DescribeCluster response.
    cluster_name     VARCHAR(100),
    cluster_endpoint VARCHAR(255),
    aws_region       VARCHAR(30),

    -- Distinct from created_at: server-side apply is idempotent and re-runs on retry, so this moves
    -- forward on every apply while created_at pins the first one.
    applied_at       TIMESTAMP   NOT NULL DEFAULT NOW(),

    created_at       TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NULL
);

COMMENT ON TABLE tenant_namespace IS
    'Durable record of the Kubernetes namespace provisioned for a tenant, written by the '
        'CREATE_NAMESPACE provisioning step in the same transaction as its step advance.';

-- Backfill from the job rows that already carry a namespace, so existing tenants are not left
-- without a record. Joined through tenants_registration because the job is keyed by company_email
-- while this table is keyed by tenant_id. Rows whose registration never reached CREATE_TENANT have
-- no tenant_id and are skipped - there is nothing to point at.
INSERT INTO tenant_namespace (tenant_id, namespace, applied_at, created_at)
SELECT r.tenant_id, p.namespace, p.created_at, p.created_at
FROM tenant_workspace_provisioning p
         JOIN tenants_registration r ON r.company_email = p.company_email
WHERE p.namespace IS NOT NULL
  AND r.tenant_id IS NOT NULL
ON CONFLICT DO NOTHING;
