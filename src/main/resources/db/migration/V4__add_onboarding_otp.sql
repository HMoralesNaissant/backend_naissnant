-- Flyway migration V4: OTP codes emailed during onboarding verification.
-- code_hash is SHA-256, but for a 6-digit code that's mostly to avoid plaintext at
-- rest, not brute-force resistance - the attempts cap plus RateLimitFilter are the
-- real protection against guessing.

CREATE TABLE onboarding_otp (
    company_email VARCHAR(200) PRIMARY KEY REFERENCES onboarding(company_email),
    code_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'created' CHECK (status IN ('created','validated','expired')),
    attempts INT NOT NULL DEFAULT 0,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    validated_at TIMESTAMP
);
