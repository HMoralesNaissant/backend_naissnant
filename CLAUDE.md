# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`tenantos-registrar` is a Spring Boot 4 (Java 21) service that handles tenant onboarding for a
multi-tenant product ("tenantos"). It is one service in a larger `multi-tenant` project — this repo
is only the registrar. Root package: `com.tenantos.registrar`.

## Common commands

```bash
# Start local Postgres (reads POSTGRES_USER/POSTGRES_PASSWORD/DB_NAME from .env)
docker compose -f compose.yml up -d

# Build
./gradlew build

# Run the app (defaults to the `local` Spring profile — see application.yaml)
./gradlew bootRun

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.tenantos.registrar.BackendApplicationTests"

# Run a single test method
./gradlew test --tests "com.tenantos.registrar.BackendApplicationTests.contextLoads"
```

There is no lint/format task configured in `build.gradle`.

## Architecture

Standard layered Spring MVC structure under `src/main/java/com/tenantos/registrar/`:

- `controllers/` — REST endpoints (`OnboardingRestController`, `AuthController`)
- `services/` — business logic (`OnboardingService`)
- `repository/` — Spring Data JPA repositories (`OnboardingRepository`)
- `entity/` — JPA entities (`Onboarding`)
- `security/` — hand-rolled JWT + rate limiting (`JwtProvider`, `JwtAuthenticationFilter`, `RateLimitFilter`)
- `config/` — `SecurityConfig` wires the above into the Spring Security filter chain
- `exceptions/` — `GlobalExceptionHandler` (`@RestControllerAdvice`) maps exceptions to JSON error bodies

### Auth model

There is no user store yet. `AuthController` checks credentials against `ADMIN_USER`/`ADMIN_PASSWORD`
env vars (defaults `admin`/`adminpass`) and issues a JWT via `JwtProvider`. `JwtAuthenticationFilter`
reads the `Authorization: Bearer` header on every request and, if valid, sets a `SecurityContext`
with a single `ROLE_USER` authority — there's no per-user identity or role model.

`SecurityConfig` builds the filter chain manually (no `WebSecurityCustomizer`/bean injection for the
filters — `JwtProvider`, `RateLimitFilter`, and `JwtAuthenticationFilter` are constructed inline reading
`JWT_SECRET`/`JWT_TTL_SECONDS` from `System.getenv()` directly rather than Spring config binding).
Public endpoints: `POST /onboarding`, `POST /auth/token`, springdoc/swagger, and `/actuator/**`.
Everything else requires a valid bearer token. CSRF uses a non-HttpOnly cookie (`XSRF-TOKEN`) for
JS frontend consumption. CORS is currently hardcoded to allow only `http://localhost:3000`.

`RateLimitFilter` is an in-memory, per-IP sliding-window limiter (10 req / 60s) scoped only to
`POST /onboarding`. It's explicitly not distributed-safe (see its class comment) — don't rely on it
surviving multiple instances/replicas.

### Onboarding flow

`OnboardingRestController` accepts `{companyEmail, otpType, otpDetails, status}`, and
`OnboardingService.onboardUser` enforces uniqueness by checking `existsById(companyEmail)` before
insert (the `onboarding` table's primary key *is* `company_email` — there's no surrogate id).
A conflict throws `DataIntegrityViolationException`, translated to HTTP 409 by
`GlobalExceptionHandler`. Swagger/OpenAPI annotations on the controller are the source of truth for
the request/response schema — keep them in sync with the record fields.

### Database & migrations

- Flyway migrations live in `src/main/resources/db/migration/` (`V1__initial_setup.sql` creates
  `onboarding` and `tenants_registration`).
- Flyway is **disabled** (`flyway.enabled: false`) under the default config in `application.yaml`,
  which instead relies on Hibernate `ddl-auto: update`. The `migration` Spring profile
  (`application-migration.yaml`) turns Flyway on — use `--spring.profiles.active=migration` (or set
  `SPRING_PROFILES_ACTIVE=migration`) when you need migrations actually applied instead of
  Hibernate auto-DDL.
- `tenants_registration` exists as a table in the migration SQL but has no corresponding JPA
  entity/repository/controller yet — if you're asked to build tenant registration (as opposed to
  onboarding), that's greenfield work on top of an existing schema, not a rename of `Onboarding`.
- `Onboarding` entity does not map the `expiration_time` column that exists in the migration SQL —
  check whether that's intentional before adding OTP-expiry logic.

### Configuration profiles

- `application.yaml` — base config, active profile defaults to `local`.
- `application-local.yaml` — local Postgres connection overrides.
- `application-migration.yaml` — same DB connection, with Flyway enabled.
- DB connection also honors `DB_URL`/`POSTGRES_USER`/`POSTGRES_PASSWORD`/`DB_NAME` env vars (see
  `.env`, consumed by `compose.yml` for the local Postgres container on port `54321`).
- Secrets (`JWT_SECRET`, `ADMIN_USER`, `ADMIN_PASSWORD`) are read directly via `System.getenv()`
  inside `SecurityConfig`/`AuthController` rather than `@Value`/`application.yaml` — check there,
  not the yaml files, when tracing how those are configured.