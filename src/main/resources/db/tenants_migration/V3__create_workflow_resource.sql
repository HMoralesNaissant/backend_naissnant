-- V3__create_workflow_resource.sql

-- ============================================================================
-- WORKSPACES
-- ============================================================================
CREATE TABLE workspaces
(
    id          UUID PRIMARY KEY,

    -- Not a real foreign key, same reasoning as users.registrar_user_id in V1: tenants lives in
    -- the registrar's own database (db/migration/V3__tenant_provisioning.sql), a different Postgres
    -- server from this per-tenant database - just a durable pointer back to it.
    tenant_id   UUID         NOT NULL,

    name        VARCHAR(200) NOT NULL,

    slug        VARCHAR(100) NOT NULL,

    description TEXT,

    status      VARCHAR(30)  NOT NULL DEFAULT 'active',

    settings    JSONB        NOT NULL DEFAULT '{}'::jsonb,

    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE (tenant_id, slug)
);

-- ============================================================================
-- FOLDERS
-- ============================================================================

CREATE TABLE folders
(
    id               UUID PRIMARY KEY,

    workspace_id     UUID         NOT NULL
        REFERENCES workspaces (id)
            ON DELETE CASCADE,

    parent_folder_id UUID
        REFERENCES folders (id)
            ON DELETE CASCADE,

    name             VARCHAR(200) NOT NULL,

    description      TEXT,

    position         INTEGER      NOT NULL DEFAULT 0,

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE (workspace_id, parent_folder_id, name)
);

-- ============================================================================
-- WORKFLOWS
-- ============================================================================

CREATE TABLE workflows
(
    id                 UUID PRIMARY KEY,

    workspace_id       UUID         NOT NULL
        REFERENCES workspaces (id)
            ON DELETE CASCADE,

    folder_id          UUID
                                    REFERENCES folders (id)
                                        ON DELETE SET NULL,

    name               VARCHAR(250) NOT NULL,

    slug               VARCHAR(150) NOT NULL,

    description        TEXT,

    status             VARCHAR(30)  NOT NULL DEFAULT 'draft',

    active             BOOLEAN      NOT NULL DEFAULT FALSE,

    current_version_id UUID,

    created_by         UUID,

    settings           JSONB        NOT NULL DEFAULT '{}'::jsonb,

    metadata           JSONB        NOT NULL DEFAULT '{}'::jsonb,

    created_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE (workspace_id, slug)
);

-- ============================================================================
-- WORKFLOW VERSIONS
-- ============================================================================

CREATE TABLE workflow_versions
(
    id             UUID PRIMARY KEY,

    workflow_id    UUID        NOT NULL
        REFERENCES workflows (id)
            ON DELETE CASCADE,

    version        INTEGER     NOT NULL,

    status         VARCHAR(30) NOT NULL DEFAULT 'draft',

    workflow_json  JSONB       NOT NULL DEFAULT '{}'::jsonb,

    change_summary TEXT,

    created_by     UUID,

    published_at   TIMESTAMPTZ,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE (workflow_id, version)
);

-- ============================================================================
-- WORKFLOW NODES
-- ============================================================================

CREATE TABLE workflow_nodes
(
    id                  UUID PRIMARY KEY,

    workflow_version_id UUID         NOT NULL
        REFERENCES workflow_versions (id)
            ON DELETE CASCADE,

    node_key            VARCHAR(100) NOT NULL,

    type                VARCHAR(100) NOT NULL,

    name                VARCHAR(200) NOT NULL,

    description         TEXT,

    -- UI canvas position
    position_x          INTEGER      NOT NULL DEFAULT 0,
    position_y          INTEGER      NOT NULL DEFAULT 0,

    -- UI dimensions
    width               INTEGER,
    height              INTEGER,

    -- Node lifecycle
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Node-specific configuration
    config              JSONB        NOT NULL DEFAULT '{}'::jsonb,

    -- Optional metadata
    metadata            JSONB        NOT NULL DEFAULT '{}'::jsonb,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE (workflow_version_id, node_key)
);

-- ============================================================================
-- WORKFLOW NODE SETTINGS
-- ============================================================================

CREATE TABLE workflow_node_settings
(
    id                  UUID PRIMARY KEY,

    node_id             UUID        NOT NULL UNIQUE
        REFERENCES workflow_nodes (id)
            ON DELETE CASCADE,

    timeout_ms          INTEGER,

    retry_enabled       BOOLEAN     NOT NULL DEFAULT FALSE,

    retry_count         INTEGER     NOT NULL DEFAULT 0,

    retry_delay_ms      INTEGER     NOT NULL DEFAULT 1000,

    retry_strategy      VARCHAR(50) NOT NULL DEFAULT 'fixed',

    continue_on_error   BOOLEAN     NOT NULL DEFAULT FALSE,

    always_execute      BOOLEAN     NOT NULL DEFAULT FALSE,

    execution_condition TEXT,

    concurrency_limit   INTEGER,

    cache_enabled       BOOLEAN     NOT NULL DEFAULT FALSE,

    cache_ttl_seconds   INTEGER,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- WORKFLOW EDGES
-- ============================================================================

CREATE TABLE workflow_edges
(
    id                  UUID PRIMARY KEY,

    workflow_version_id UUID         NOT NULL
        REFERENCES workflow_versions (id)
            ON DELETE CASCADE,

    source_node_id      UUID         NOT NULL
        REFERENCES workflow_nodes (id)
            ON DELETE CASCADE,

    target_node_id      UUID         NOT NULL
        REFERENCES workflow_nodes (id)
            ON DELETE CASCADE,

    source_port         VARCHAR(100) NOT NULL DEFAULT 'output',

    target_port         VARCHAR(100) NOT NULL DEFAULT 'input',

    edge_type           VARCHAR(50)  NOT NULL DEFAULT 'success',

    label               VARCHAR(200),

    config              JSONB        NOT NULL DEFAULT '{}'::jsonb,

    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CHECK (source_node_id <> target_node_id)
);

-- ============================================================================
-- VARIABLES
-- ============================================================================

CREATE TABLE variables
(
    id           UUID PRIMARY KEY,

    workspace_id UUID         NOT NULL
        REFERENCES workspaces (id)
            ON DELETE CASCADE,

    name         VARCHAR(200) NOT NULL,

    value        TEXT         NOT NULL,

    encrypted    BOOLEAN      NOT NULL DEFAULT FALSE,

    UNIQUE (workspace_id, name)
);

-- ============================================================================
-- CONNECTIONS
-- ============================================================================
CREATE TABLE connections
(
    id           UUID PRIMARY KEY,

    workspace_id UUID         NOT NULL
        REFERENCES workspaces (id)
            ON DELETE CASCADE,

    name         VARCHAR(200) NOT NULL,

    type         VARCHAR(100) NOT NULL,

    provider     VARCHAR(100) NOT NULL,

    credentials  JSONB        NOT NULL DEFAULT '{}'::jsonb,

    encrypted    BOOLEAN      NOT NULL DEFAULT TRUE,

    status       VARCHAR(30)  NOT NULL DEFAULT 'active',

    metadata     JSONB        NOT NULL DEFAULT '{}'::jsonb,

    created_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE (workspace_id, name)
);

CREATE TABLE workflow_node_connections
(
    node_id       UUID NOT NULL
        REFERENCES workflow_nodes (id)
            ON DELETE CASCADE,

    connection_id UUID NOT NULL
        REFERENCES connections (id)
            ON DELETE CASCADE,

    purpose       VARCHAR(100) DEFAULT 'default',

    PRIMARY KEY (node_id, connection_id)
);

CREATE TABLE connection_types
(
    id         UUID PRIMARY KEY,

    type       VARCHAR(100) UNIQUE NOT NULL,

    provider   VARCHAR(100)        NOT NULL,

    schema     JSONB               NOT NULL,

    created_at TIMESTAMPTZ         NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- WORKFLOW TRIGGERS
-- ============================================================================

CREATE TABLE workflow_triggers
(
    id          UUID PRIMARY KEY,

    workflow_id UUID        NOT NULL
        REFERENCES workflows (id)
            ON DELETE CASCADE,

    type        VARCHAR(50) NOT NULL,

    config      JSONB       NOT NULL,

    enabled     BOOLEAN     NOT NULL DEFAULT TRUE
);

-- ============================================================================
-- WEBHOOKS
-- ============================================================================

CREATE TABLE webhooks
(
    id                    UUID PRIMARY KEY,

    workflow_id           UUID         NOT NULL
        REFERENCES workflows (id)
            ON DELETE CASCADE,

    trigger_id            UUID         NOT NULL
        REFERENCES workflow_triggers (id)
            ON DELETE CASCADE,

    name                  VARCHAR(200) NOT NULL,

    path                  VARCHAR(255) NOT NULL UNIQUE,

    method                VARCHAR(10)  NOT NULL DEFAULT 'POST',

    authentication_type   VARCHAR(50)  NOT NULL DEFAULT 'none',

    authentication_config JSONB        NOT NULL DEFAULT '{}'::jsonb,

    response_mode         VARCHAR(50)  NOT NULL DEFAULT 'immediate',

    response_config       JSONB        NOT NULL DEFAULT '{}'::jsonb,

    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,

    created_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- SCHEDULES
-- ============================================================================

CREATE TABLE schedules
(
    id              UUID PRIMARY KEY,

    workflow_id     UUID         NOT NULL
        REFERENCES workflows (id)
            ON DELETE CASCADE,

    trigger_id      UUID         NOT NULL
        REFERENCES workflow_triggers (id)
            ON DELETE CASCADE,

    name            VARCHAR(200) NOT NULL,

    type            VARCHAR(50)  NOT NULL DEFAULT 'cron',

    cron_expression VARCHAR(100),

    misfire_policy  VARCHAR(30)           DEFAULT 'execute_once' interval_value  INTEGER,

    interval_unit   VARCHAR(20),

    timezone        VARCHAR(100) NOT NULL DEFAULT 'UTC',

    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,

    next_run_at     TIMESTAMPTZ,

    last_run_at     TIMESTAMPTZ,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- EXECUTION QUEUE
-- ============================================================================

CREATE TABLE execution_queue
(
    id                  UUID PRIMARY KEY,

    workflow_id         UUID        NOT NULL
        REFERENCES workflows (id)
            ON DELETE CASCADE,

    workflow_version_id UUID        NOT NULL
        REFERENCES workflow_versions (id)
            ON DELETE CASCADE,

    trigger_type        VARCHAR(50) NOT NULL,

    trigger_id          UUID,

    payload             JSONB       NOT NULL DEFAULT '{}'::jsonb,

    priority            INTEGER     NOT NULL DEFAULT 0,

    status              VARCHAR(30) NOT NULL DEFAULT 'pending',

    attempts            INTEGER     NOT NULL DEFAULT 0,

    max_attempts        INTEGER     NOT NULL DEFAULT 3,

    available_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    started_at          TIMESTAMPTZ,

    completed_at        TIMESTAMPTZ,

    locked_by           VARCHAR(100),

    locked_until        TIMESTAMPTZ,

    error_message       TEXT,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- ============================================================================
-- EXECUTIONS
-- ============================================================================

CREATE TABLE workflow_executions
(
    id            UUID PRIMARY KEY,

    workflow_id   UUID        NOT NULL
        REFERENCES workflows (id),

    version       INTEGER     NOT NULL,

    status        VARCHAR(30) NOT NULL,

    started_at    TIMESTAMP   NOT NULL,

    finished_at   TIMESTAMP,

    duration_ms   BIGINT,

    trigger_type  VARCHAR(50),

    input         JSONB,

    output        JSONB,

    error_message TEXT
);

-- ============================================================================
-- EXECUTION STEPS
-- ============================================================================

CREATE TABLE execution_steps
(
    id             UUID PRIMARY KEY,

    execution_id   UUID        NOT NULL
        REFERENCES workflow_executions (id)
            ON DELETE CASCADE,

    node_id        UUID        NOT NULL
        REFERENCES workflow_nodes (id),

    parent_step_id UUID
        REFERENCES execution_steps (id),

    status         VARCHAR(30) NOT NULL DEFAULT 'pending',

    attempt        INTEGER     NOT NULL DEFAULT 1,

    started_at     TIMESTAMPTZ,

    finished_at    TIMESTAMPTZ,

    duration_ms    BIGINT,

    input          JSONB       NOT NULL DEFAULT '{}'::jsonb,

    output         JSONB       NOT NULL DEFAULT '{}'::jsonb,

    error_code     VARCHAR(100),

    error_message  TEXT,

    metadata       JSONB       NOT NULL DEFAULT '{}'::jsonb,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- INDEXES
-- ============================================================================

CREATE INDEX idx_workspaces_tenant
    ON workspaces (tenant_id);

CREATE INDEX idx_folders_workspace
    ON folders (workspace_id);

CREATE INDEX idx_workflows_workspace
    ON workflows (workspace_id);

CREATE INDEX idx_workflows_folder
    ON workflows (folder_id);

CREATE INDEX idx_versions_workflow
    ON workflow_versions (workflow_id);

CREATE INDEX idx_nodes_version
    ON workflow_nodes (workflow_version_id);

CREATE INDEX idx_nodes_type
    ON workflow_nodes (type);

CREATE INDEX idx_edges_version
    ON workflow_edges (workflow_version_id);

CREATE INDEX idx_edges_source
    ON workflow_edges (source_node_id);

CREATE INDEX idx_edges_target
    ON workflow_edges (target_node_id);

CREATE INDEX idx_tags_workspace
    ON tags (workspace_id);

CREATE INDEX idx_variables_workspace
    ON variables (workspace_id);

CREATE INDEX idx_connections_workspace
    ON connections (workspace_id);

CREATE INDEX idx_triggers_workflow
    ON workflow_triggers (workflow_id);

CREATE INDEX idx_webhooks_workflow
    ON webhooks (workflow_id);

CREATE INDEX idx_schedules_workflow
    ON schedules (workflow_id);

CREATE INDEX idx_queue_status
    ON execution_queue (status);

CREATE INDEX idx_queue_available
    ON execution_queue (available_at);

CREATE INDEX idx_execution_workflow
    ON workflow_executions (workflow_id);

CREATE INDEX idx_execution_status
    ON workflow_executions (status);

CREATE INDEX idx_execution_started
    ON workflow_executions (started_at DESC);

CREATE INDEX idx_execution_steps_execution
    ON execution_steps (execution_id);

CREATE INDEX idx_workflow_nodes_version
    ON workflow_nodes (workflow_version_id);

CREATE INDEX idx_workflow_nodes_type
    ON workflow_nodes (type);