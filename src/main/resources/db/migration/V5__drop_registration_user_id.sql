-- Flyway migration V5: drop tenants_registration.user_id
--
-- The column was denormalization, not a relationship. users.email is UNIQUE and is always the
-- registration's company_email, so the user is reachable from the registration by lookup - the FK
-- only duplicated that, and gave the provisioning pipeline a second thing to keep in step.
--
-- Provisioning steps now resolve the user via UserRepository.findByEmail(company_email).

ALTER TABLE tenants_registration
    DROP COLUMN user_id;
