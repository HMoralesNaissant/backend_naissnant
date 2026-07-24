-- Flyway migration V1: initial
-- Create onboarding and tenants_registration tables per specification

-- onboarding
CREATE TABLE onboarding (
    company_email VARCHAR(200) PRIMARY KEY,
    otp_type VARCHAR(50) NOT NULL DEFAULT 'code',
    otp_details JSON NOT NULL DEFAULT '{}'::json,
    expiration_time TIMESTAMP NOT NULL DEFAULT (NOW() + INTERVAL '60 minutes'),
    status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active','completed','otp-validation','expired'))
);

-- tenants_registration
CREATE TABLE tenants_registration (
    company_email VARCHAR(200) PRIMARY KEY,
    full_name VARCHAR(200),
    password VARCHAR(200),
    account_name VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active','completed'))
);

CREATE TABLE onboarding_token (
                                  token_hash VARCHAR(64) PRIMARY KEY,
                                  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                                  expires_at TIMESTAMP NOT NULL,
                                  used_at TIMESTAMP
);

CREATE TABLE onboarding_token_request (
                                          token_hash VARCHAR(64) PRIMARY KEY REFERENCES onboarding_token(token_hash),
                                          client_details JSON NOT NULL DEFAULT '{}'::json,
                                          requested_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE onboarding_otp (
                                otp_id varchar(200) primary key,
                                company_email VARCHAR(200),
                                code_hash VARCHAR(64) NOT NULL,
                                status VARCHAR(20) NOT NULL DEFAULT 'created' CHECK (status IN ('created','validated','expired')),
                                attempts INT NOT NULL DEFAULT 0,
                                expires_at TIMESTAMP NOT NULL,
                                created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                                validated_at TIMESTAMP
);
