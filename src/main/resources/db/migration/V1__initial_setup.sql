-- Flyway migration V1: initial
-- Create onboarding and tenants_registration tables per specification

-- onboarding
CREATE TABLE onboarding
(
    company_email   VARCHAR(200) PRIMARY KEY,
    otp_type        VARCHAR(50) NOT NULL DEFAULT 'code',
    otp_details     JSON        NOT NULL DEFAULT '{}'::json,
    expiration_time TIMESTAMP   NOT NULL DEFAULT (NOW() + INTERVAL '60 minutes'),
    status          VARCHAR(20) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'otp-validated', 'completed', 'expired')),
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NULL
);

CREATE TABLE onboarding_otp
(
    otp_id        varchar(200) primary key,
    company_email VARCHAR(200),
    code_hash     VARCHAR(64) NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'created' CHECK (status IN ('created', 'invalidated', 'validated', 'expired')),
    attempts      INT         NOT NULL DEFAULT 0,
    expires_at    TIMESTAMP   NOT NULL,
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NULL,
    validated_at  TIMESTAMP
);


-- tenants_registration
CREATE TABLE tenants_registration
(
    company_email VARCHAR(200) PRIMARY KEY,
    full_name     VARCHAR(200),
    password      VARCHAR(200),
    account_name  VARCHAR(100),
    status        VARCHAR(20) NOT NULL DEFAULT 'onboarding' CHECK (status IN ('onboarding', 'completed')),
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NULL
);

CREATE TABLE onboarding_token
(
    token_hash     VARCHAR(64) PRIMARY KEY,
    client_details JSON      NOT NULL DEFAULT '{}'::json,
    expires_at     TIMESTAMP NOT NULL,
    used_at        TIMESTAMP,
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP NULL
);

