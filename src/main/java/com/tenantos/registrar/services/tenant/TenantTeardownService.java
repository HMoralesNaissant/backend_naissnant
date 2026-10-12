package com.tenantos.registrar.services.tenant;

import com.tenantos.registrar.domain.response.TenantTeardownResponse;
import com.tenantos.registrar.entity.Tenant;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.repository.OnboardingOtpRepository;
import com.tenantos.registrar.repository.OnboardingRepository;
import com.tenantos.registrar.repository.TenantMemberRepository;
import com.tenantos.registrar.repository.TenantRepository;
import com.tenantos.registrar.repository.TenantWorkspaceProvisioningRepository;
import com.tenantos.registrar.repository.TenantsRegistrationRepository;
import com.tenantos.registrar.repository.UserRepository;
import com.tenantos.registrar.services.database.TenantDatabaseProvisioner;
import com.tenantos.registrar.services.infisical.InfisicalClient;
import com.tenantos.registrar.services.kubernetes.NamespaceProvisioner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Destroys everything provisioning created for a tenant: the Kubernetes namespace (and the apps in
 * it), the Postgres database and role, the Infisical folder, and finally the registrar's own rows.
 *
 * <p><b>Order.</b> The provisioning job goes first so the scheduler cannot resume the pipeline and
 * recreate what is being removed. External resources follow, and the tenant's rows go last: they
 * are the only record of which slug to clean, so a failure part-way leaves the tenant findable and
 * the call can simply be repeated - every step here tolerates its target already being gone.
 *
 * <p>Not a single transaction, because most of this is not a database. Only the final row removal
 * is atomic.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TenantTeardownService {

  private final TenantSlugGenerator tenantSlugGenerator;
  private final TenantRepository tenantRepository;
  private final TenantsRegistrationRepository registrationRepository;
  private final TenantWorkspaceProvisioningRepository provisioningRepository;
  private final TenantMemberRepository memberRepository;
  private final UserRepository userRepository;
  private final OnboardingRepository onboardingRepository;
  private final OnboardingOtpRepository onboardingOtpRepository;
  private final NamespaceProvisioner namespaceProvisioner;
  private final TenantDatabaseProvisioner tenantDatabaseProvisioner;
  private final InfisicalClient infisicalClient;
  private final TransactionTemplate transactionTemplate;

  @Value("${tenant.database.enabled:true}")
  private boolean databaseEnabled;

  @Value("${infisical.enabled:false}")
  private boolean infisicalEnabled;

  /**
   * @throws IllegalArgumentException if the name has nothing to build a slug from
   * @throws NoSuchElementException if no tenant has that slug
   */
  public TenantTeardownResponse destroy(String accountName) {
    String slug = tenantSlugGenerator.normalize(accountName);
    if (slug.isEmpty()) {
      throw new IllegalArgumentException("accountName must contain at least one letter or digit");
    }
    Tenant tenant =
        tenantRepository
            .findBySlug(slug)
            .orElseThrow(() -> new NoSuchElementException("No tenant with slug " + slug));
    List<TenantsRegistration> registrations = registrationRepository.findByTenantId(tenant.getId());

    log.warn("Tearing down tenant {} ({})", slug, tenant.getId());

    // Stop the pipeline before anything is removed, or a retry could rebuild it behind us.
    transactionTemplate.executeWithoutResult(
        tx ->
            registrations.forEach(
                r -> provisioningRepository.deleteByCompanyEmail(r.getCompanyEmail())));

    boolean namespaceDeleted = namespaceProvisioner.destroyWorkspace(tenant);

    boolean databaseDropped = false;
    if (databaseEnabled) {
      tenantDatabaseProvisioner.drop(slug);
      databaseDropped = true;
    }

    boolean secretsDeleted = false;
    if (infisicalEnabled) {
      infisicalClient.deleteFolder("/" + slug);
      secretsDeleted = true;
    }

    transactionTemplate.executeWithoutResult(tx -> deleteRecords(tenant, registrations));

    log.warn("Tenant {} torn down", slug);
    return new TenantTeardownResponse(slug, namespaceDeleted, databaseDropped, secretsDeleted, true);
  }

  private void deleteRecords(Tenant tenant, List<TenantsRegistration> registrations) {
    // The tenant's dependants (members, roles, subscription, api keys, namespace/database rows,
    // audit log) go with it through ON DELETE CASCADE.
    registrationRepository.deleteAll(registrations);
    registrationRepository.flush();
    tenantRepository.delete(tenant);
    tenantRepository.flush();

    for (TenantsRegistration registration : registrations) {
      String email = registration.getCompanyEmail();
      userRepository
          .findByEmail(email)
          .filter(user -> !memberRepository.existsByUserId(user.getId()))
          .ifPresent(userRepository::delete);
      onboardingOtpRepository.deleteByCompanyEmail(email);
      onboardingRepository.deleteById(email);
    }
  }
}
