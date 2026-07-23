-- Flyway migration V2: preflight token table for the unauthenticated onboarding gate.
-- Not related to the onboarding table's own otp_type/otp_details/expiration_time columns,
-- which are for a later, per-company_email OTP verification step.

CREATE TABLE onboarding_token (
    token_hash VARCHAR(64) PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP
);
