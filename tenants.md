
### Prompt

I already have a production-ready application with authentication and a registration flow. **Do NOT recreate authentication, registration, or the project structure.**
Your task is to design and implement **the post-registration tenant provisioning flow** that executes **immediately after a successful registration**.
## Context

The registration process has already:

* Created the user account
* Verified the email (or will do so before this flow)
* Authenticated the user

Once registration succeeds, we must provision everything required for a brand new tenant.

This is **not** a signup implementation.
This is the continuation that begins after registration has completed successfully.

---

# Objective

Design a robust tenant provisioning workflow that creates all required tenant resources
The workflow should be idempotent and safe against retries.

---

# Provisioning Flow

After registration:

## 1. Create Tenant

Generate a new tenant record.

Fields should include things like:

* id (UUID)
* name
* slug
* status
* plan
* timezone
* locale
* metadata
* created_at
* updated_at

Slug generation should:

* normalize text
* remove special characters
* avoid collisions
* append suffixes when needed

---

## 2. Associate User with Tenant

Create the membership record.

Example fields:

* tenant_id
* user_id
* role
* status
* invited_by
* joined_at

The registering user automatically becomes:

* Owner
* Active

---

## 3. Create Roles

Bootstrap default roles.

Example:

Owner

Administrator

Manager

Member

Viewer

Roles should be tenant scoped.

---

## 4. Generate Permissions

Seed default permissions.

Examples:

Users

Roles

Billing

Settings

Products

Orders

Reports

Inventory

API Keys

Webhooks

Permissions should support RBAC.

---

## 5. Create Role Assignments

Assign Owner role to the creator.

---

## 6. Authentication

If authentication already exists:

Create any missing tenant-aware authentication records such as:

* refresh token entries
* active session
* login audit
* device registration
* security metadata

Do NOT recreate users.

---

## 7. Refresh Tokens

Persist refresh token metadata.

Include:

* expiration
* revocation support
* rotation
* device fingerprint
* IP
* user agent

---

## 8. Session

Create the initial authenticated session.

Track:

* login timestamp
* last activity
* expiration
* revoked flag

---

## 9. Audit Logs

Generate audit events.

Examples:

TenantCreated

OwnerAssigned

RegistrationCompleted

RoleProvisioned

PermissionProvisioned

---

## 11. Subscription

Create an initial subscription.

Example:

Free Trial

or

Free Plan

Include:

plan

trial expiration

status

limits

---

## 12. API Keys

If applicable:

Generate an initial internal API key.


# Concurrency

Prevent duplicate tenant creation if the registration callback executes twice.
---

# Database Design

Provide the schema for all required tables involved in provisioning, including relationships, indexes, unique constraints, and foreign keys.

At minimum include:

* tenants
* tenant_members
* roles
* permissions
* role_permissions
* user_roles
* refresh_tokens
* sessions
* login_audit
* subscriptions
* tenant_settings
* api_keys
* audit_logs

Explain why each table exists.

---

# API Design

Design the application service responsible for provisioning.

Example:

```
TenantProvisioningService
    provisionNewTenant(user, registrationData)
```

Describe each dependency.

---

# Sequence Diagram

Provide a sequence diagram illustrating the provisioning workflow from the moment registration completes until the user receives a successful response.

---

# Production Considerations

Discuss:

* transactions
* optimistic vs pessimistic locking
* retries
* distributed events
* observability
* tracing
* metrics
* structured logging
* monitoring
* scalability
* horizontal scaling
* eventual consistency
* background jobs for non-critical tasks

The solution should be production-grade and follow modern SaaS multi-tenant architecture best practices.
