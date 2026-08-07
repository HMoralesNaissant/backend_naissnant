
enum UserStatus {
  INVITED /// row exists, no password set, cannot authenticate
  ACTIVE
  SUSPENDED

  @@map("user_status")
}

model User {
id String @id @default(uuid()) @db.Uuid

/// The id of this person's row in the registrar's own `users` table. Not a real foreign key -
/// the registrar and this tenant's database are different Postgres servers - just a durable
/// pointer for cross-referencing audit trails and support tooling.
registrarUserId String @unique @map("registrar_user_id") @db.Uuid

/// Stored lowercase and trimmed — normalisation happens in the Email value
/// object, never in a controller, so every write path gets it.
///
/// Uniqueness is unconditional rather than "unique among non-deleted rows",
/// because Postgres partial unique indexes aren't expressible in Prisma
/// schema. Soft-deleting therefore rewrites the address to
/// `<id>@deleted.invalid` (see UserRepository.softDelete), which frees **the**
/// address for re-registration and doubles as GDPR erasure of the identifier.
email        String  @unique @db.VarChar(320)
/// Argon2id encoded hash (`$argon2id$v=19$m=...`). Never leaves the repository
/// layer — UserResponse has no field for it.
passwordHash String? @map("password_hash") @db.VarChar(255)

firstName String?    @map("first_name") @db.VarChar(80)
lastName  String?    @map("last_name") @db.VarChar(80)
status    UserStatus @default(ACTIVE)

/// Platform staff. Deliberately a column and not a MembershipRole: it grants
/// cross-tenant access, which is a different axis from a role inside a tenant.
isPlatformAdmin Boolean @default(false) @map("is_platform_admin")

/// Bumped on password change, forced logout, or role change. Access tokens
/// carry the value they were minted with; the JWT strategy rejects any token
/// whose value is stale. This is what makes stateless access tokens revocable
/// without a Redis round trip on the hot path.
tokenVersion Int @default(0) @map("token_version")

lastLoginAt DateTime? @map("last_login_at") @db.Timestamptz(6)

version   Int       @default(0)
createdAt DateTime  @default(now()) @map("created_at") @db.Timestamptz(6)
updatedAt DateTime  @updatedAt @map("updated_at") @db.Timestamptz(6)
deletedAt DateTime? @map("deleted_at") @db.Timestamptz(6)

refreshTokens RefreshToken[]

/// Composite index for keyset pagination: `ORDER BY created_at DESC, id DESC`
/// walks this index directly, so page 10 000 costs the same as page 1.
@@index([createdAt(sort: Desc), id(sort: Desc)])
@@index([status])
@@index([deletedAt])
@@map("users")
}

// -----------------------------------------------------------------------------
// RefreshToken — one row per issued refresh token. Rotation and reuse detection
// are explained in docs/AUTH.md; the short version is that each login opens a
// `familyId`, every refresh closes one row and opens the next in the same
// family, and presenting an already-rotated token nukes the whole family.
// -----------------------------------------------------------------------------

model RefreshToken {
id     String @id @default(uuid()) @db.Uuid
userId String @map("user_id") @db.Uuid

/// All rotations descending from a single login share this. Revoking a family
/// logs out that one device without touching the user's other sessions.
familyId String @map("family_id") @db.Uuid

/// SHA-256 of the token, hex. The raw token is never stored: a database dump
/// must not be enough to impersonate a user. SHA-256 rather than Argon2 is
/// deliberate — the token is 256 bits of CSPRNG output, so it isn't
/// brute-forceable and the lookup stays a single indexed read.
tokenHash String @unique @map("token_hash") @db.Char(64)

expiresAt     DateTime  @map("expires_at") @db.Timestamptz(6)
revokedAt     DateTime? @map("revoked_at") @db.Timestamptz(6)
/// Free-text audit trail: "rotated", "logout", "reuse-detected", "password-changed".
revokedReason String?   @map("revoked_reason") @db.VarChar(64)

/// Weak device fingerprint, for the "your sessions" screen and abuse triage.
userAgent String? @map("user_agent") @db.VarChar(255)
ipAddress String? @map("ip_address") @db.Inet

createdAt DateTime @default(now()) @map("created_at") @db.Timestamptz(6)

user User @relation(fields: [userId], references: [id], onDelete: Cascade)

/// Active-session lookup for one user.
@@index([userId, expiresAt])
/// Family revocation on reuse detection.
@@index([familyId])
/// Drives the expired-token sweeper.
@@index([expiresAt])
@@map("refresh_tokens")
}
