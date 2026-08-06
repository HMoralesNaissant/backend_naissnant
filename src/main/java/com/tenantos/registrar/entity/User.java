package com.tenantos.registrar.entity;

import com.tenantos.registrar.enums.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the users table (V3 migration): identity, separate from the onboarding funnel.
 *
 * <p>This is the one place a password hash lives - {@code tenants_registration.password} was
 * dropped by V3 in favour of {@link #passwordHash} here.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class User extends BaseAuditFields {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "email", length = 200, nullable = false, unique = true)
  private String email;

  // Always a BCrypt hash, via the PasswordEncoder bean in SecurityConfig - never plaintext.
  @Column(name = "password_hash", length = 200, nullable = false)
  private String passwordHash;

  @Column(name = "full_name", length = 200)
  private String fullName;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 20, nullable = false)
  @Default
  private UserStatus status = UserStatus.ACTIVE;

  /**
   * Set at registration rather than left for a later step: reaching account registration already
   * required a validated OTP, so the address is verified by construction.
   */
  @Column(name = "email_verified_at")
  private Instant emailVerifiedAt;
}
