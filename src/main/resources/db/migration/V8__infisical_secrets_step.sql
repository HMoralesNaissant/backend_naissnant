-- Flyway migration V8: PROVISION_INFISICAL_SECRETS pipeline step
--
-- Slotted between CREATE_TENANT_DATABASE and SEND_CONFIRMATION. Jobs already at or past
-- SEND_CONFIRMATION never run it; jobs parked earlier pick it up on their next advance.
ALTER TABLE tenant_workspace_provisioning
    DROP CONSTRAINT tenant_workspace_provisioning_current_step_check;

-- ALTER TABLE tenant_workspace_provisioning
--     ADD CONSTRAINT tenant_workspace_provisioning_current_step_check
--         CHECK (current_step IN ('CREATE_USER', 'CREATE_TENANT', 'SEED_RBAC', 'CREATE_MEMBERSHIP',
--                                 'CREATE_SUBSCRIPTION', 'CREATE_API_KEY', 'CREATE_NAMESPACE',
--                                 'CREATE_TENANT_DATABASE', 'PROVISION_INFISICAL_SECRETS',
--                                 'SEND_CONFIRMATION', 'COMPLETED'));
