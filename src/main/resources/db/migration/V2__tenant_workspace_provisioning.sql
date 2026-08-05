-- Flyway migration V2: background tenant workspace provisioning
-- Workspace (EKS namespace) creation is too slow and too failure-prone to run inside the
-- registration transaction, so it becomes a durable job claimed by a poller.

-- Denormalized copy of the provisioning job's status, so reading a tenant's workspace state
-- doesn't require joining the job table.
ALTER TABLE tenants_registration
    ADD COLUMN workspace_status VARCHAR(30) NOT NULL DEFAULT 'WORKSPACE_PENDING'
        CHECK (workspace_status IN ('WORKSPACE_PENDING', 'WORKSPACE_IN_PROGRESS',
                                    'WORKSPACE_READY', 'WORKSPACE_FAILED'));

-- One row per tenant (company_email is UNIQUE), holding the retry bookkeeping the
-- denormalized column above deliberately doesn't carry.
CREATE TABLE tenant_workspace_provisioning
(
    provisioning_id VARCHAR(64) PRIMARY KEY,
    company_email   VARCHAR(200) NOT NULL UNIQUE REFERENCES tenants_registration (company_email),
    status          VARCHAR(30)  NOT NULL DEFAULT 'WORKSPACE_PENDING'
        CHECK (status IN ('WORKSPACE_PENDING', 'WORKSPACE_IN_PROGRESS',
                          'WORKSPACE_READY', 'WORKSPACE_FAILED')),
    namespace       VARCHAR(63),
    attempts        INT          NOT NULL DEFAULT 0,
    max_attempts    INT          NOT NULL DEFAULT 5,
    next_attempt_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    claimed_at      TIMESTAMP,
    completed_at    TIMESTAMP,
    last_error      TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NULL
);

-- Supports the poller's claim query, which filters on status + next_attempt_at.
CREATE INDEX idx_twp_claimable ON tenant_workspace_provisioning (status, next_attempt_at);
