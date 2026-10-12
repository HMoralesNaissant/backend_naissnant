-- V3__create_workflow_resource.sql
create table connection_types
(
    id         uuid                                               not null
        primary key,
    type       varchar(100)                                       not null
        unique,
    provider   varchar(100)                                       not null,
    schema     jsonb                                              not null,
    created_at timestamp with time zone default CURRENT_TIMESTAMP not null,
    name       varchar(200)                                       not null
);

create table node_types
(
    id           uuid                     default gen_random_uuid() not null
        constraint node_types_pk
            primary key,
    name         varchar(100)                                       not null,
    display_name varchar(200)                                       not null,
    version      integer                  default 1                 not null,
    description  varchar(200),
    inputs       jsonb                    default '[]'::jsonb,
    outputs      jsonb                    default '[]'::jsonb       not null,
    credentials  jsonb                    default '[]'::jsonb       not null,
    properties   jsonb                    default '[]'::jsonb       not null,
    icon         jsonb                    default '{}'::jsonb       not null,
    category     jsonb                    default '[]'::jsonb       not null,
    metadata     jsonb                    default '{}'::jsonb       not null,
    created_at   timestamp with time zone default CURRENT_TIMESTAMP not null,
    updated_at   timestamp with time zone default CURRENT_TIMESTAMP not null,
    enabled      boolean                  default false             not null
);

comment on table node_types is 'Node Types';

create table workflow_versions
(
    id             uuid                                                        not null
        primary key,
    workflow_id    uuid                                                        not null,
    version        integer                                                     not null,
    status         varchar(30)              default 'draft'::character varying not null,
    workflow_json  jsonb                    default '{}'::jsonb                not null,
    change_summary text,
    created_by     uuid,
    published_at   timestamp with time zone,
    created_at     timestamp with time zone default CURRENT_TIMESTAMP          not null,
    unique (workflow_id, version)
);

create table workflow_nodes
(
    id                  uuid                                               not null
        primary key,
    workflow_version_id uuid                                               not null
        references workflow_versions
            on delete cascade,
    node_key            varchar(100)                                       not null,
    type                varchar(100)                                       not null,
    name                varchar(200)                                       not null,
    description         text,
    position_x          integer                  default 0                 not null,
    position_y          integer                  default 0                 not null,
    width               integer,
    height              integer,
    enabled             boolean                  default true              not null,
    config              jsonb                    default '{}'::jsonb       not null,
    metadata            jsonb                    default '{}'::jsonb       not null,
    created_at          timestamp with time zone default CURRENT_TIMESTAMP not null,
    updated_at          timestamp with time zone default CURRENT_TIMESTAMP not null,
    unique (workflow_version_id, node_key)
);

create table workflow_edges
(
    id                  uuid                                              not null
        primary key,
    workflow_version_id uuid                                              not null
        references workflow_versions
            on delete cascade,
    source_node_id      uuid                                              not null
        references workflow_nodes
            on delete cascade,
    target_node_id      uuid                                              not null
        references workflow_nodes
            on delete cascade,
    source_port         varchar(100) default 'output'::character varying  not null,
    target_port         varchar(100) default 'input'::character varying   not null,
    edge_type           varchar(50)  default 'success'::character varying not null,
    label               varchar(200),
    config              jsonb        default '{}'::jsonb                  not null,
    created_at          timestamp    default CURRENT_TIMESTAMP            not null,
    constraint workflow_edges_check
        check (source_node_id <> target_node_id)
);

create index idx_edges_source
    on workflow_edges (source_node_id);

create index idx_edges_target
    on workflow_edges (target_node_id);

create index idx_edges_version
    on workflow_edges (workflow_version_id);

create table workflow_node_settings
(
    id                  uuid                                                        not null
        primary key,
    node_id             uuid                                                        not null
        unique
        references workflow_nodes
            on delete cascade,
    timeout_ms          integer,
    retry_enabled       boolean                  default false                      not null,
    retry_count         integer                  default 0                          not null,
    retry_delay_ms      integer                  default 1000                       not null,
    retry_strategy      varchar(50)              default 'fixed'::character varying not null,
    continue_on_error   boolean                  default false                      not null,
    always_execute      boolean                  default false                      not null,
    execution_condition text,
    concurrency_limit   integer,
    cache_enabled       boolean                  default false                      not null,
    cache_ttl_seconds   integer,
    created_at          timestamp with time zone default CURRENT_TIMESTAMP          not null,
    updated_at          timestamp with time zone default CURRENT_TIMESTAMP          not null
);

create index idx_nodes_type
    on workflow_nodes (type);

create index idx_nodes_version
    on workflow_nodes (workflow_version_id);

create index idx_workflow_nodes_type
    on workflow_nodes (type);

create index idx_workflow_nodes_version
    on workflow_nodes (workflow_version_id);

create index idx_versions_workflow
    on workflow_versions (workflow_id);

create table workspaces
(
    id          uuid                                                         not null
        primary key,
    user_id     uuid                                                         not null,
    name        varchar(200)                                                 not null,
    slug        varchar(100)                                                 not null,
    description text,
    status      varchar(30)              default 'active'::character varying not null,
    is_default  boolean                  default false                       not null,
    settings    jsonb                    default '{}'::jsonb                 not null,
    created_at  timestamp with time zone default CURRENT_TIMESTAMP           not null,
    updated_at  timestamp with time zone default CURRENT_TIMESTAMP           not null,
    unique (user_id, slug)
);

create table folders
(
    id           uuid                                               not null
        primary key,
    workspace_id uuid                                               not null
        references workspaces
            on delete cascade,
    name         varchar(200)                                       not null,
    slug         varchar(200)                                       not null,
    description  text,
    position     integer                  default 0                 not null,
    is_default   boolean                  default false             not null,
    created_at   timestamp with time zone default CURRENT_TIMESTAMP not null,
    updated_at   timestamp with time zone default CURRENT_TIMESTAMP not null,
    unique (workspace_id, name),
    unique (workspace_id, slug)
);

create table connections
(
    id           uuid                                                         not null
        primary key,
    workspace_id uuid                                                         not null
        references workspaces
            on delete cascade,
    folder_id    uuid                                                         not null
        references folders
            on delete set null,
    name         varchar(200)                                                 not null,
    type         varchar(100)                                                 not null,
    provider     varchar(100)                                                 not null,
    credentials  jsonb                    default '{}'::jsonb                 not null,
    encrypted    boolean                  default true                        not null,
    status       varchar(30)              default 'active'::character varying not null,
    metadata     jsonb                    default '{}'::jsonb                 not null,
    created_at   timestamp with time zone default CURRENT_TIMESTAMP           not null,
    updated_at   timestamp with time zone default CURRENT_TIMESTAMP           not null,
    constraint connections_pk
        unique (workspace_id, folder_id, name)
);

create index idx_connections_workspace
    on connections (workspace_id);

create index idx_folders_workspace
    on folders (workspace_id);

create table variables
(
    id           uuid                                               not null
        primary key,
    workspace_id uuid                                               not null
        references workspaces
            on delete cascade,
    folder_id    uuid                                               not null
        references folders
            on delete set null,
    name         varchar(200)                                       not null,
    value        text                                               not null,
    encrypted    boolean                  default false             not null,
    created_at   timestamp with time zone default CURRENT_TIMESTAMP not null,
    updated_at   timestamp with time zone default CURRENT_TIMESTAMP not null
);


create index idx_variables_workspace
    on variables (workspace_id);

create unique index variables_workspace_id_folde_id_name_key
    on variables (workspace_id, folder_id, name);

create table workflow_node_connections
(
    node_id       uuid not null
        references workflow_nodes
            on delete cascade,
    connection_id uuid not null
        references connections
            on delete cascade,
    purpose       varchar(100) default 'default'::character varying,
    primary key (node_id, connection_id)
);


create table workflows
(
    id                 uuid                                                        not null
        primary key,
    workspace_id       uuid                                                        not null
        references workspaces
            on delete cascade,
    folder_id          uuid
                                                                                   references folders
                                                                                       on delete set null,
    name               varchar(250)                                                not null,
    slug               varchar(150)                                                not null,
    description        text,
    status             varchar(30)              default 'draft'::character varying not null,
    active             boolean                  default false                      not null,
    current_version_id uuid
        constraint workflows_workflow_versions_id_fk
            references workflow_versions,
    created_by         uuid,
    settings           jsonb                    default '{}'::jsonb                not null,
    metadata           jsonb                    default '{}'::jsonb                not null,
    created_at         timestamp with time zone default CURRENT_TIMESTAMP          not null,
    updated_at         timestamp with time zone default CURRENT_TIMESTAMP          not null,
    constraint workflows_pk
        unique (workspace_id, folder_id, name)
);

create table execution_queue
(
    id                  uuid                                                          not null
        primary key,
    workflow_id         uuid                                                          not null
        references workflows
            on delete cascade,
    workflow_version_id uuid                                                          not null
        references workflow_versions
            on delete cascade,
    trigger_type        varchar(50)                                                   not null,
    trigger_id          uuid,
    payload             jsonb                    default '{}'::jsonb                  not null,
    priority            integer                  default 0                            not null,
    status              varchar(30)              default 'pending'::character varying not null,
    attempts            integer                  default 0                            not null,
    max_attempts        integer                  default 3                            not null,
    available_at        timestamp with time zone default CURRENT_TIMESTAMP            not null,
    started_at          timestamp with time zone,
    completed_at        timestamp with time zone,
    locked_by           varchar(100),
    locked_until        timestamp with time zone,
    error_message       text,
    created_at          timestamp with time zone default CURRENT_TIMESTAMP            not null,
    updated_at          timestamp with time zone default CURRENT_TIMESTAMP            not null
);


create index idx_queue_available
    on execution_queue (available_at);

create index idx_queue_status
    on execution_queue (status);

create table workflow_executions
(
    id            uuid        not null
        primary key,
    workflow_id   uuid        not null
        references workflows,
    version       integer     not null,
    status        varchar(30) not null,
    started_at    timestamp   not null,
    finished_at   timestamp,
    duration_ms   bigint,
    trigger_type  varchar(50),
    input         jsonb,
    output        jsonb,
    error_message text
);


create table execution_steps
(
    id             uuid                                                          not null
        primary key,
    execution_id   uuid                                                          not null
        references workflow_executions
            on delete cascade,
    node_id        uuid                                                          not null
        references workflow_nodes,
    parent_step_id uuid
        references execution_steps,
    status         varchar(30)              default 'pending'::character varying not null,
    attempt        integer                  default 1                            not null,
    started_at     timestamp with time zone,
    finished_at    timestamp with time zone,
    duration_ms    bigint,
    input          jsonb                    default '{}'::jsonb                  not null,
    output         jsonb                    default '{}'::jsonb                  not null,
    error_code     varchar(100),
    error_message  text,
    metadata       jsonb                    default '{}'::jsonb                  not null,
    created_at     timestamp with time zone default CURRENT_TIMESTAMP            not null
);

create index idx_execution_steps_execution
    on execution_steps (execution_id);

create index idx_execution_started
    on workflow_executions (started_at desc);

create index idx_execution_status
    on workflow_executions (status);

create index idx_execution_workflow
    on workflow_executions (workflow_id);

create table workflow_triggers
(
    id          uuid                 not null
        primary key,
    workflow_id uuid                 not null
        references workflows
            on delete cascade,
    type        varchar(50)          not null,
    config      jsonb                not null,
    enabled     boolean default true not null
);

create table schedules
(
    id              uuid                                                       not null
        primary key,
    workflow_id     uuid                                                       not null
        references workflows
            on delete cascade,
    trigger_id      uuid                                                       not null
        references workflow_triggers
            on delete cascade,
    name            varchar(200)                                               not null,
    type            varchar(50)              default 'cron'::character varying not null,
    cron_expression varchar(100),
    misfire_policy  varchar(30)              default 'execute_once'::character varying,
    interval_value  integer,
    interval_unit   varchar(20),
    timezone        varchar(100)             default 'UTC'::character varying  not null,
    enabled         boolean                  default true                      not null,
    next_run_at     timestamp with time zone,
    last_run_at     timestamp with time zone,
    created_at      timestamp with time zone default CURRENT_TIMESTAMP         not null,
    updated_at      timestamp with time zone default CURRENT_TIMESTAMP         not null
);

create index idx_schedules_workflow
    on schedules (workflow_id);

create table webhooks
(
    id                    uuid                                                            not null
        primary key,
    workflow_id           uuid                                                            not null
        references workflows
            on delete cascade,
    trigger_id            uuid                                                            not null
        references workflow_triggers
            on delete cascade,
    name                  varchar(200)                                                    not null,
    path                  varchar(255)                                                    not null
        unique,
    method                varchar(10)              default 'POST'::character varying      not null,
    authentication_type   varchar(50)              default 'none'::character varying      not null,
    authentication_config jsonb                    default '{}'::jsonb                    not null,
    response_mode         varchar(50)              default 'immediate'::character varying not null,
    response_config       jsonb                    default '{}'::jsonb                    not null,
    enabled               boolean                  default true                           not null,
    created_at            timestamp with time zone default CURRENT_TIMESTAMP              not null,
    updated_at            timestamp with time zone default CURRENT_TIMESTAMP              not null
);


create index idx_webhooks_workflow
    on webhooks (workflow_id);

create index idx_triggers_workflow
    on workflow_triggers (workflow_id);

alter table workflow_versions
    add foreign key (workflow_id) references workflows
        on delete cascade;

create index idx_workflows_folder
    on workflows (folder_id);

create index idx_workflows_workspace
    on workflows (workspace_id);

create index idx_workspaces_user
    on workspaces (user_id);

