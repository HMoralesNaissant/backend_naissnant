package com.tenantos.registrar.services.tenant.steps.data_provision;

import com.tenantos.registrar.entity.TenantWorkspaceProvisioning;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.entity.User;
import com.tenantos.registrar.enums.ProvisioningStep;
import com.tenantos.registrar.enums.UserStatus;
import com.tenantos.registrar.services.tenant.AbstractTransactionalStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Step 1: create the identity behind the registration.
 *
 * <p>The password arrives already hashed, staged on {@code tenants_registration.password_hash} by
 * {@code OnboardingService.register()}. It has to work that way round: bcrypt needs the plaintext,
 * which only exists in memory during the registration request, so hashing cannot be deferred to a
 * background step even though creating the user can be. This step consumes the staged hash and
 * clears it in the same transaction, so a hash sits in two places only for the seconds between the
 * registration commit and this running.
 *
 * <p>{@code email_verified_at} is set rather than left null because reaching account registration
 * already required a validated OTP - the address is verified by construction.
 *
 * <p>Re-running after a partial failure is safe for the usual pipeline reason: the user insert, the
 * {@code user_id} link, and the {@code current_step} advance all commit together, so a retry starts
 * from a state where none of them happened.
 */
@Component
@Slf4j
public class CreateUserStep extends AbstractTransactionalStep {

  @Override
  public ProvisioningStep step() {
    return ProvisioningStep.CREATE_USER;
  }

  @Override
  protected void doExecute(TenantWorkspaceProvisioning job, TenantsRegistration registration) {
    log.info("Creating user for tenant {}", registration.getTenantId());
    String stagedHash = registration.getPasswordHash();
    if (stagedHash == null || stagedHash.isBlank()) {
      throw new IllegalStateException(
          "No staged password hash for "
              + registration.getCompanyEmail()
              + " - registration did not stage one, or CREATE_USER already consumed it");
    }

    User currentUser = userRepository.findByEmail(registration.getCompanyEmail()).orElse(null);
    if (currentUser != null) {
      log.info(
          "User {} already exists for {}", currentUser.getId(), registration.getCompanyEmail());
      return;
    }

    User user =
        userRepository.save(
            User.builder()
                .email(registration.getCompanyEmail())
                .passwordHash(stagedHash)
                .fullName(registration.getFullName())
                .status(UserStatus.ACTIVE)
                .emailVerifiedAt(Instant.now())
                .build());

    // Consumed. From here users.password_hash is the only copy.
    registration.setPasswordHash(null);

    log.info("Created user {} for {}", user.getId(), registration.getCompanyEmail());
  }
}
