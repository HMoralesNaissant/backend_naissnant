-- Flyway migration V9: DISPATCH_TENANT_DEPLOY and AWAIT_TENANT_DEPLOY pipeline steps
--
-- Slotted between CREATE_TENANT_DATABASE and SEND_CONFIRMATION. Jobs already at or past
-- SEND_CONFIRMATION never run them, so tenants provisioned before this migration have no apps
-- deployed; rewind their current_step to DISPATCH_TENANT_DEPLOY to pick them up.
--
-- V8 dropped this constraint and left re-adding it commented out, so there is nothing to drop
-- here on a database that applied V8 - IF EXISTS keeps this valid either way. When a
-- PROVISION_INFISICAL_SECRETS step is added to ProvisioningStep, its migration must add it to this
-- list.
ALTER TABLE tenant_workspace_provisioning
    DROP CONSTRAINT IF EXISTS tenant_workspace_provisioning_current_step_check;

ALTER TABLE tenant_workspace_provisioning
    ADD CONSTRAINT tenant_workspace_provisioning_current_step_check
        CHECK (current_step IN ('CREATE_USER', 'CREATE_TENANT', 'SEED_RBAC', 'CREATE_MEMBERSHIP',
                                'CREATE_SUBSCRIPTION', 'CREATE_API_KEY', 'CREATE_NAMESPACE',
                                'CREATE_TENANT_DATABASE', 'DISPATCH_TENANT_DEPLOY',
                                'AWAIT_TENANT_DEPLOY', 'SEND_CONFIRMATION', 'COMPLETED'));
