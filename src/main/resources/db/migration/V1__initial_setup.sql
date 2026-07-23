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