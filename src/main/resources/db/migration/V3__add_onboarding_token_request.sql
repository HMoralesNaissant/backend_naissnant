-- Flyway migration V3: logs the requesting client's browser details for every
-- preflight onboarding token issued, one row per GET /onboarding/token call.
-- client_details is a JSON blob (not typed columns) so new fields can be captured
-- later without another migration.

CREATE TABLE onboarding_token_request (
    token_hash VARCHAR(64) PRIMARY KEY REFERENCES onboarding_token(token_hash),
    client_details JSON NOT NULL DEFAULT '{}'::json,
    requested_at TIMESTAMP NOT NULL DEFAULT NOW()
);
