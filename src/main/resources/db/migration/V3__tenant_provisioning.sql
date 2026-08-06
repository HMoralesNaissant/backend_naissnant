-- Flyway migration V3: post-registration tenant provisioning
--
-- Adds the tenant model the provisioning pipeline builds: identity (users), the tenant root and
-- its settings, RBAC (roles/permissions/assignments), tenant-aware auth records
-- (sessions/refresh_tokens/login_audit), commercial state (subscriptions), machine credentials
-- (api_keys), and the business audit trail (audit_logs).
--
-- Ordering matters: users -> tenants -> everything that references them, and sessions before
-- refresh_tokens (which FKs to it).
--
-- Postgres 16, so gen_random_uuid() is built in - no pgcrypto extension needed.

-- ---------------------------------------------------------------------------
-- Identity
-- ---------------------------------------------------------------------------

-- Split out of tenants_registration, which is the onboarding funnel record rather than an
-- identity. A user outlives any single tenant and may eventually belong to several, which is why
-- this gets a surrogate UUID instead of keying on email like the funnel tables do.
CREATE TABLE users
(
    id                UUID PRIMARY KEY   DEFAULT gen_random_uuid(),
    email             VARCHAR(200) NOT NULL UNIQUE,
    password_hash     VARCHAR(200) NOT NULL,
    full_name         VARCHAR(200),
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DISABLED')),
    email_verified_at TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NULL
);

-- Existing registrations become users, carrying their bcrypt hash across before the old column
-- is dropped below. Rows with no password predate the credential flow and are skipped rather
-- than given an unusable empty hash.
INSERT INTO users (email, password_hash, full_name, email_verified_at, created_at)
SELECT company_email, password, full_name, created_at, created_at
FROM tenants_registration
WHERE password IS NOT NULL
ON CONFLICT (email) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Tenant root
-- ---------------------------------------------------------------------------

-- slug is capped at 63 so it can be used directly as an RFC 1123 DNS label - the tenant's
-- Kubernetes namespace is "tenant-<slug>". UNIQUE here is what makes that namespace name
-- collision-free without any further checking.
CREATE TABLE tenants
(
    id         UUID PRIMARY KEY   DEFAULT gen_random_uuid(),
    name       VARCHAR(200) NOT NULL,
    slug       VARCHAR(63)  NOT NULL UNIQUE,
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    plan       VARCHAR(30)  NOT NULL DEFAULT 'FREE_TRIAL',
    timezone   VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    locale     VARCHAR(10)  NOT NULL DEFAULT 'en-US',
    metadata   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NULL
);

-- 1:1 with tenants, split off because settings are read-modify-write per tenant while the
-- tenants row itself is near-immutable. PK = tenant_id makes the provisioning step that creates
-- it idempotent by construction.
CREATE TABLE tenant_settings
(
    tenant_id  UUID PRIMARY KEY REFERENCES tenants (id) ON DELETE CASCADE,
    timezone   VARCHAR(64) NOT NULL DEFAULT 'UTC',
    locale     VARCHAR(10) NOT NULL DEFAULT 'en-US',
    branding   JSONB       NOT NULL DEFAULT '{}'::jsonb,
    features   JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NULL
);

-- Membership has its own lifecycle (invited -> active -> suspended) independent of both the user
-- and the tenant, so it is a table rather than a column on either. The UNIQUE constraint is the
-- concurrency guard: a duplicated provisioning callback cannot create a second membership.
CREATE TABLE tenant_members
(
    id         UUID PRIMARY KEY  DEFAULT gen_random_uuid(),
    tenant_id  UUID        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role       VARCHAR(30) NOT NULL DEFAULT 'MEMBER',
    status     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'REMOVED')),
    invited_by UUID REFERENCES users (id) ON DELETE SET NULL,
    joined_at  TIMESTAMP,
    created_at TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NULL,
    UNIQUE (tenant_id, user_id)
);

CREATE INDEX idx_tenant_members_user ON tenant_members (user_id);

-- ---------------------------------------------------------------------------
-- RBAC
-- ---------------------------------------------------------------------------

-- Tenant-scoped: each tenant gets its own five system roles, so a tenant can later rename them or
-- add custom roles without touching anyone else's.
CREATE TABLE roles
(
    id          UUID PRIMARY KEY  DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name        VARCHAR(50) NOT NULL,
    description VARCHAR(255),
    is_system   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NULL,
    UNIQUE (tenant_id, name)
);

-- Deliberately NOT tenant-scoped. Permissions are a fixed resource:action vocabulary defined by
-- the application, identical for every tenant - copying all 50 rows per tenant would multiply the
-- catalog with no gain. Tenancy lives in roles; this is the vocabulary they draw from.
CREATE TABLE permissions
(
    id          UUID PRIMARY KEY  DEFAULT gen_random_uuid(),
    code        VARCHAR(80) NOT NULL UNIQUE,
    resource    VARCHAR(50) NOT NULL,
    action      VARCHAR(20) NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NULL,
    UNIQUE (resource, action)
);

-- Composite PK rather than a surrogate id: re-running the RBAC seed can only ever conflict, never
-- duplicate.
CREATE TABLE role_permissions
(
    role_id       UUID NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions (id) ON DELETE CASCADE,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (role_id, permission_id)
);

-- Carries tenant_id even though it is derivable through roles, so authorization checks
-- ("what may this user do in this tenant?") are a single indexed lookup with no join.
CREATE TABLE user_roles
(
    user_id     UUID      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id     UUID      NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    tenant_id   UUID      NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    assigned_by UUID REFERENCES users (id) ON DELETE SET NULL,
    assigned_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_user_roles_user_tenant ON user_roles (user_id, tenant_id);

-- ---------------------------------------------------------------------------
-- Tenant-aware authentication
-- ---------------------------------------------------------------------------

-- Server-side session state. Access tokens are stateless JWTs, which cannot be revoked once
-- issued; this row is what makes logout and forced sign-out actually mean something.
CREATE TABLE sessions
(
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    tenant_id          UUID      NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    login_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    last_activity_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at         TIMESTAMP NOT NULL,
    revoked_at         TIMESTAMP,
    ip_address         VARCHAR(45),
    user_agent         VARCHAR(512),
    device_fingerprint VARCHAR(128),
    created_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP NULL
);

CREATE INDEX idx_sessions_user ON sessions (user_id, revoked_at);

-- Only the SHA-256 hash is stored, same approach as onboarding_token.token_hash - a database leak
-- must not yield usable refresh tokens. rotated_to chains each token to its successor, which is
-- what makes reuse detection possible: presenting a token that has already been rotated means the
-- token was captured, so the whole chain gets revoked.
CREATE TABLE refresh_tokens
(
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash         VARCHAR(64) NOT NULL UNIQUE,
    user_id            UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    tenant_id          UUID        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    session_id         UUID REFERENCES sessions (id) ON DELETE CASCADE,
    expires_at         TIMESTAMP   NOT NULL,
    revoked_at         TIMESTAMP,
    rotated_to         UUID REFERENCES refresh_tokens (id) ON DELETE SET NULL,
    device_fingerprint VARCHAR(128),
    ip_address         VARCHAR(45),
    user_agent         VARCHAR(512),
    created_at         TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP NULL
);

CREATE INDEX idx_refresh_tokens_session ON refresh_tokens (session_id);

-- Every authentication attempt, successful or not. user_id is nullable on purpose: a failed login
-- for an address that does not exist still needs recording, and the attempted email is kept
-- separately so the trail survives user deletion.
CREATE TABLE login_audit
(
    id         UUID PRIMARY KEY   DEFAULT gen_random_uuid(),
    user_id    UUID REFERENCES users (id) ON DELETE SET NULL,
    email      VARCHAR(200) NOT NULL,
    tenant_id  UUID REFERENCES tenants (id) ON DELETE SET NULL,
    outcome    VARCHAR(30)  NOT NULL
        CHECK (outcome IN ('SUCCESS', 'INVALID_CREDENTIALS', 'USER_DISABLED',
                           'NO_TENANT', 'TOKEN_REUSE_DETECTED')),
    ip_address VARCHAR(45),
    user_agent VARCHAR(512),
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_login_audit_email_created ON login_audit (email, created_at DESC);

-- ---------------------------------------------------------------------------
-- Commercial state
-- ---------------------------------------------------------------------------

CREATE TABLE subscriptions
(
    id                   UUID PRIMARY KEY  DEFAULT gen_random_uuid(),
    tenant_id            UUID        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    plan                 VARCHAR(30) NOT NULL DEFAULT 'FREE_TRIAL',
    status               VARCHAR(20) NOT NULL DEFAULT 'TRIALING'
        CHECK (status IN ('TRIALING', 'ACTIVE', 'PAST_DUE', 'CANCELED', 'EXPIRED')),
    trial_ends_at        TIMESTAMP,
    current_period_start TIMESTAMP   NOT NULL DEFAULT NOW(),
    current_period_end   TIMESTAMP,
    limits               JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at           TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP NULL
);

-- History is kept (canceled/expired rows stay), but only one subscription may be live at a time.
-- A partial unique index expresses that directly, and doubles as the idempotency guard for the
-- provisioning step that creates the trial.
CREATE UNIQUE INDEX uq_subscriptions_live ON subscriptions (tenant_id)
    WHERE status IN ('TRIALING', 'ACTIVE');

-- ---------------------------------------------------------------------------
-- Machine credentials
-- ---------------------------------------------------------------------------

-- key_hash is the SHA-256 of the full key; the plaintext is never stored. key_prefix is the short
-- displayable fragment ("tos_live_ab...") so a key can be identified in a UI without revealing it.
CREATE TABLE api_keys
(
    id           UUID PRIMARY KEY   DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name         VARCHAR(100) NOT NULL,
    key_prefix   VARCHAR(16)  NOT NULL,
    key_hash     VARCHAR(64)  NOT NULL UNIQUE,
    scopes       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_by   UUID REFERENCES users (id) ON DELETE SET NULL,
    last_used_at TIMESTAMP,
    expires_at   TIMESTAMP,
    revoked_at   TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NULL,
    UNIQUE (tenant_id, name)
);

-- ---------------------------------------------------------------------------
-- Audit trail
-- ---------------------------------------------------------------------------

-- Append-only business events, distinct from login_audit (which is security-specific and queried
-- on different axes). No updated_at: nothing here is ever modified.
CREATE TABLE audit_logs
(
    id            UUID PRIMARY KEY  DEFAULT gen_random_uuid(),
    tenant_id     UUID REFERENCES tenants (id) ON DELETE CASCADE,
    actor_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    event_type    VARCHAR(60) NOT NULL,
    resource_type VARCHAR(60),
    resource_id   VARCHAR(64),
    payload       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    ip_address    VARCHAR(45),
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_logs_tenant_created ON audit_logs (tenant_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Wire the existing funnel tables into the new model
-- ---------------------------------------------------------------------------

ALTER TABLE tenants_registration
    ADD COLUMN user_id   UUID REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN tenant_id UUID REFERENCES tenants (id) ON DELETE SET NULL;

UPDATE tenants_registration r
SET user_id = u.id
FROM users u
WHERE u.email = r.company_email;

-- Credentials now live on users. Dropping this leaves exactly one place a password hash exists.
ALTER TABLE tenants_registration
    DROP COLUMN password;

-- Where the provisioning pipeline resumes after a failure. Existing rows restart at the first
-- step, which is correct: none of them ran under the pipeline.
ALTER TABLE tenant_workspace_provisioning
    ADD COLUMN current_step VARCHAR(40) NOT NULL DEFAULT 'CREATE_TENANT'
        CHECK (current_step IN ('CREATE_TENANT', 'SEED_RBAC', 'CREATE_MEMBERSHIP',
                                'CREATE_SUBSCRIPTION', 'CREATE_API_KEY', 'CREATE_NAMESPACE',
                                'SEND_CONFIRMATION', 'COMPLETED'));

-- ---------------------------------------------------------------------------
-- Permission catalog: 10 resources x 5 actions
-- ---------------------------------------------------------------------------

INSERT INTO permissions (code, resource, action, description)
SELECT r.resource || ':' || a.action,
       r.resource,
       a.action,
       initcap(a.action) || ' ' || r.resource
FROM (VALUES ('users'), ('roles'), ('billing'), ('settings'), ('products'),
             ('orders'), ('reports'), ('inventory'), ('api_keys'), ('webhooks')) AS r(resource),
     (VALUES ('read'), ('create'), ('update'), ('delete'), ('manage')) AS a(action)
ON CONFLICT (code) DO NOTHING;
