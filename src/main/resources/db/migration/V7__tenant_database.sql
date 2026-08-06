-- Flyway migration V7: per-tenant Postgres database
--
-- A registered tenant got a Kubernetes namespace and nowhere to put data. CREATE_TENANT_DATABASE
-- fills that in: a dedicated database and login role per tenant, on a server separate from this
-- one. This table records where that database is; the credential itself is published to AWS
-- Secrets Manager and to a Kubernetes Secret, and is deliberately not stored here.

-- ---------------------------------------------------------------------------
-- The new pipeline step
-- ---------------------------------------------------------------------------

-- Slotted between CREATE_NAMESPACE and SEND_CONFIRMATION. Existing in-flight jobs are unaffected:
-- a job parked at CREATE_NAMESPACE picks the new step up on its next advance, and one already at
-- SEND_CONFIRMATION has passed the insertion point and simply never runs it. The latter means any
-- tenant provisioned before this migration has no database - see the note at the bottom.
ALTER TABLE tenant_workspace_provisioning
    DROP CONSTRAINT tenant_workspace_provisioning_current_step_check;

ALTER TABLE tenant_workspace_provisioning
    ADD CONSTRAINT tenant_workspace_provisioning_current_step_check
        CHECK (current_step IN ('CREATE_USER', 'CREATE_TENANT', 'SEED_RBAC', 'CREATE_MEMBERSHIP',
                                'CREATE_SUBSCRIPTION', 'CREATE_API_KEY', 'CREATE_NAMESPACE',
                                'CREATE_TENANT_DATABASE', 'SEND_CONFIRMATION', 'COMPLETED'));

-- ---------------------------------------------------------------------------
-- Where the tenant's database lives
-- ---------------------------------------------------------------------------

CREATE TABLE tenant_database
(
    -- Keyed on tenant_id, like tenant_settings (V3) and tenant_namespace (V6): 1:1 with the tenant,
    -- which is what makes a CREATE_TENANT_DATABASE retry upsert instead of appending.
    tenant_id        UUID PRIMARY KEY REFERENCES tenants (id) ON DELETE CASCADE,

    -- Both UNIQUE for the same reason tenant_namespace.namespace is: two tenants sharing a database
    -- or a login role is a cross-tenant data leak, not a duplicate row. 63 is Postgres NAMEDATALEN.
    database_name    VARCHAR(63)  NOT NULL UNIQUE,
    role_name        VARCHAR(63)  NOT NULL UNIQUE,

    -- What a tenant workload dials. Not necessarily how the registrar reached the server to create
    -- the database - in-cluster pods resolve a service name, the registrar may come via a bastion.
    host             VARCHAR(255) NOT NULL,
    port             INT          NOT NULL,

    -- Pointers to the credential, never the credential. There is deliberately NO password column
    -- here: the password is generated, published, and forgotten by this service, exactly as
    -- CreateApiKeyStep discards its plaintext. If you find yourself wanting to add one, what you
    -- actually want is to read the Secrets Manager value at the ARN below.
    --
    -- All nullable because either publisher can be switched off (locally Secrets Manager is).
    secret_arn       VARCHAR(2048),
    secret_name      VARCHAR(253),
    secret_namespace VARCHAR(63),

    -- Moves forward on every (re)provision; created_at pins the first one.
    provisioned_at   TIMESTAMP    NOT NULL DEFAULT NOW(),

    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NULL
);

COMMENT ON TABLE tenant_database IS
    'Where a tenant''s dedicated Postgres database lives. Written by the CREATE_TENANT_DATABASE '
        'provisioning step. Holds pointers to the credential, never the credential itself.';

-- No backfill is possible. Unlike V6, which could recover namespaces from the job rows, nothing
-- here can be reconstructed: the databases do not exist yet, and creating them needs a password
-- that only the running step can generate and publish. Tenants provisioned before this migration
-- need their jobs rewound to CREATE_TENANT_DATABASE to pick one up.
