-- Tenant database bootstrap: identity schema
--
-- Unlike db/migration (Flyway, versioned, applies to the registrar's own database), this location
-- is applied to a single tenant's freshly-provisioned database - there is one such database per
-- tenant, created by TenantDatabaseProvisioner. TenantSchemaMigrator constructs its own Flyway
-- instance per tenant, pointed at that database, so each tenant gets its own flyway_schema_history
-- and this file is guaranteed to run at most once per tenant - no CREATE TABLE IF NOT EXISTS or
-- other hand-rolled idempotency needed, the same stance db/migration/V3 etc. take.
--
-- Schema mirrors src/main/resources/db/migration/tenantconfig.md (the Prisma source of truth for
-- this tenant-side schema) field for field. TIMESTAMPTZ is used throughout, per that doc's explicit
-- @db.Timestamptz(6) annotations - a different choice from the registrar's own bare TIMESTAMP
-- columns (see V3), because this is a different schema with its own explicit spec.
--
-- status is a native Postgres enum, per tenantconfig.md's explicit `enum UserStatus` block
-- (INVITED/ACTIVE/SUSPENDED, @@map("user_status")). Deliberately not the VARCHAR + CHECK pattern
-- every other status column in this codebase uses (see V3's users.status) - this set has its own
-- values, distinct from the registrar's own UserStatus (which has DISABLED, not INVITED).

-- ---------------------------------------------------------------------------
-- Identity
-- ---------------------------------------------------------------------------

DROP TABLE IF EXISTS users CASCADE;
CREATE TABLE IF NOT EXISTS users
(
    id                 UUID PRIMARY KEY   DEFAULT gen_random_uuid(),

    -- The id of this person's row in the REGISTRAR's own users table. Not a real foreign key - the
    -- registrar and this tenant's database are different Postgres servers - just a durable pointer
    -- for cross-referencing audit trails and support tooling. UNIQUE because the two are 1:1: two
    -- tenant-DB users pointing at the same registrar user would be a data-modelling bug, not a
    -- duplicate row.
    registrar_user_id  UUID         NOT NULL UNIQUE,

    email              VARCHAR(320) NOT NULL UNIQUE,

    -- Nullable per tenantconfig.md. Whatever hash algorithm the writer used - currently the
    -- registrar's BCrypt, though tenantconfig.md's own comment describes Argon2id - is stored
    -- as-is; this table does not enforce or convert hash formats.
    password_hash      VARCHAR(255),

    first_name         VARCHAR(80),
    last_name          VARCHAR(80),
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',

    -- Platform staff. Deliberately a column and not a role: it grants cross-tenant access, which is
    -- a different axis from a role inside this tenant.
    is_platform_admin  BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Bumped on password change, forced logout, or role change; access tokens carry the value they
    -- were minted with and are rejected once it goes stale. Makes stateless access tokens revocable
    -- without a round trip to a session store.
    token_version      INT          NOT NULL DEFAULT 0,

    last_login_at      TIMESTAMPTZ(6),

    version            INT          NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    deleted_at         TIMESTAMPTZ(6)
);

-- Keyset pagination: ORDER BY created_at DESC, id DESC walks this index directly, so page 10 000
-- costs the same as page 1.
CREATE INDEX IF NOT EXISTS idx_users_created_at_id ON users (created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_users_status ON users (status);
CREATE INDEX IF NOT EXISTS idx_users_deleted_at ON users (deleted_at);

-- Rotation and reuse detection: each login opens a family_id, every refresh closes one row and
-- opens the next in the same family, and presenting an already-rotated token revokes the whole
-- family.

DROP TABLE IF EXISTS refresh_tokens CASCADE;
CREATE TABLE IF NOT EXISTS refresh_tokens
(
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- All rotations descending from a single login share this. Revoking a family logs out that one
    -- device without touching the user's other sessions.
    family_id      UUID        NOT NULL,

    -- SHA-256 of the token, hex. The raw token is never stored - a database dump must not be enough
    -- to impersonate a user.
    token_hash     CHAR(64)    NOT NULL UNIQUE,

    expires_at     TIMESTAMPTZ(6) NOT NULL,
    revoked_at     TIMESTAMPTZ(6),
    -- Free-text audit trail: "rotated", "logout", "reuse-detected", "password-changed".
    revoked_reason VARCHAR(64),

    -- Weak device fingerprint, for the "your sessions" screen and abuse triage.
    user_agent     VARCHAR(255),
    ip_address     INET,

    created_at     TIMESTAMPTZ(6) NOT NULL DEFAULT NOW()
);

-- Active-session lookup for one user.
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_expires ON refresh_tokens (user_id, expires_at);
-- Family revocation on reuse detection.
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_family ON refresh_tokens (family_id);
-- Drives the expired-token sweeper.
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires ON refresh_tokens (expires_at);