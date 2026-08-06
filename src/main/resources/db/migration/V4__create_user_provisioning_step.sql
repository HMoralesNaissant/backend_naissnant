-- Flyway migration V4: move user creation into the provisioning pipeline
--
-- User creation moves out of OnboardingService.register() and becomes CREATE_USER, the first step
-- of the pipeline, so registration is purely "validate and enqueue" and the pipeline owns every
-- entity it creates.
--
-- That move has one consequence this migration exists to handle. Bcrypt hashing needs the
-- plaintext password, which only exists in memory during the registration request - a background
-- step running seconds later cannot hash anything. So register() still hashes, and stages the
-- result here for CREATE_USER to consume.

-- Staging column, not a credential store. Written by register(), read once by CREATE_USER, and
-- nulled in that same transaction - so a hash lives here only for the seconds between the
-- registration commit and the step running. users.password_hash remains the permanent home; V3's
-- intent (exactly one place a credential lives at rest) is preserved.
ALTER TABLE tenants_registration
    ADD COLUMN password_hash VARCHAR(200);

COMMENT ON COLUMN tenants_registration.password_hash IS
    'Transient handoff to the CREATE_USER provisioning step. Nulled once the user exists; a '
        'non-null value on a COMPLETED registration means CREATE_USER never ran.';

-- CREATE_USER becomes the new first step, ahead of CREATE_TENANT.
ALTER TABLE tenant_workspace_provisioning
    DROP CONSTRAINT tenant_workspace_provisioning_current_step_check;

ALTER TABLE tenant_workspace_provisioning
    ALTER COLUMN current_step SET DEFAULT 'CREATE_USER';

ALTER TABLE tenant_workspace_provisioning
    ADD CONSTRAINT tenant_workspace_provisioning_current_step_check
        CHECK (current_step IN ('CREATE_USER', 'CREATE_TENANT', 'SEED_RBAC', 'CREATE_MEMBERSHIP',
                                'CREATE_SUBSCRIPTION', 'CREATE_API_KEY', 'CREATE_NAMESPACE',
                                'SEND_CONFIRMATION', 'COMPLETED'));
