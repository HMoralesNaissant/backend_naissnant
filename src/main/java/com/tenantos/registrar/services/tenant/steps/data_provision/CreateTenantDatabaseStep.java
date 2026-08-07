package com.tenantos.registrar.services.tenant.steps.data_provision;

import com.tenantos.registrar.entity.AuditLog;
import com.tenantos.registrar.entity.Tenant;
import com.tenantos.registrar.entity.TenantDatabase;
import com.tenantos.registrar.entity.TenantWorkspaceProvisioning;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.enums.AuditEventType;
import com.tenantos.registrar.enums.ProvisioningStep;
import com.tenantos.registrar.repository.AuditLogRepository;
import com.tenantos.registrar.repository.TenantDatabaseRepository;
import com.tenantos.registrar.repository.TenantNamespaceRepository;
import com.tenantos.registrar.repository.TenantRepository;
import com.tenantos.registrar.repository.TenantsRegistrationRepository;
import com.tenantos.registrar.repository.UserRepository;
import com.tenantos.registrar.entity.User;
import com.tenantos.registrar.services.aws.TenantEksSecretWriter;
import com.tenantos.registrar.services.aws.TenantDbSecretsManagerPublisher;
import com.tenantos.registrar.services.database.ProvisionedTenantDatabase;
import com.tenantos.registrar.services.database.TenantDatabaseProvisioner;
import com.tenantos.registrar.services.database.TenantSchemaMigrator;
import com.tenantos.registrar.services.tenant.TenantProvisioningStep;
import com.tenantos.registrar.services.tenant.steps.records.ProvisionedAwsSecret;
import com.tenantos.registrar.services.workspace.TenantWorkspaceProvisioningStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Step 8: give the tenant a database of their own, and publish the credential where their workloads
 * can reach it.
 *
 * <p>Not transactional, and it cannot be: {@code CREATE DATABASE} is rejected inside a transaction
 * block, so this step cannot extend {@code AbstractTransactionalStep} and inherit the atomic
 * work-plus-advance the database steps enjoy. It takes the {@link SendConfirmationStep} shape
 * instead - do the external work, then advance by hand - and pays for it with idempotency: every
 * piece of work here converges rather than assuming it runs once.
 *
 * <p><b>Order matters.</b> The credential is published before the row that records it. A crash
 * between the two leaves a working database and a published password with no local record, which
 * the retry fixes by regenerating and republishing. The reverse order would leave a row claiming a
 * database is ready while nobody holds a password that opens it.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CreateTenantDatabaseStep implements TenantProvisioningStep {

  @Value("${tenant.database.enabled:true}")
  private boolean enabled;

  private final TenantsRegistrationRepository tenantsRegistrationRepository;
  private final TenantRepository tenantRepository;

  private final TenantDatabaseRepository tenantDatabaseRepository;
  private final UserRepository userRepository;
  private final AuditLogRepository auditLogRepository;
  private final TenantDatabaseProvisioner tenantDatabaseProvisioner;
  private final TenantSchemaMigrator tenantSchemaMigrator;
  private final TenantEksSecretWriter tenantEksSecretWriter;
  // Absent unless tenant.database.secret.aws-secrets-manager-enabled is true - the publisher and
  // its
  // client bean are both conditional, so environments with no AWS never construct either.
  private final Optional<TenantDbSecretsManagerPublisher> secretsManagerPublisher;
  private final TenantWorkspaceProvisioningStore store;

  @Override
  public ProvisioningStep step() {
    return ProvisioningStep.CREATE_TENANT_DATABASE;
  }

  @Override
  public void execute(TenantWorkspaceProvisioning job) {
    if (!enabled) {
      // Advance rather than throw. An environment with no tenant-data server configured would
      // otherwise burn every retry here and dead-letter every tenant.
      log.warn(
          "tenant.database.enabled is false - skipping database provisioning for {}",
          job.getCompanyEmail());
      store.advance(job.getProvisioningId(), step().next(), null);
      return;
    }

    TenantsRegistration registration =
        tenantsRegistrationRepository
            .findById(job.getCompanyEmail())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No tenants_registration row for " + job.getCompanyEmail()));

    Tenant tenant =
        tenantRepository
            .findById(registration.getTenantId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Tenant "
                            + registration.getTenantId()
                            + " not found - CREATE_TENANT did not run"));

    log.info(
        "Provisioning database for tenant {} (provisioning_id {})",
        job.getCompanyEmail(),
        job.getProvisioningId());

    ProvisionedTenantDatabase provisioned = tenantDatabaseProvisioner.provision(tenant);

    User user =
        userRepository
            .findByEmail(registration.getCompanyEmail())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No user for "
                            + registration.getCompanyEmail()
                            + " - CREATE_USER did not run"));
    tenantSchemaMigrator.migrate(provisioned, user, tenant);

    // System of record first, mirror second. If only one write lands, the retry overwrites both.
    String secretArn =
        secretsManagerPublisher
            .map(publisher -> publisher.publish(tenant, provisioned))
            .orElse(null);

    ProvisionedAwsSecret secret = tenantEksSecretWriter.write(tenant, provisioned);

    // Loaded before writing rather than rebuilt: saving a freshly built entity merges over the
    // stored row and carries the new instance's created_at with it, losing the first provision's
    // timestamp - the same trap already fixed once in CreateNamespaceStep.
    TenantDatabase tenantDatabase =
        tenantDatabaseRepository
            .findById(tenant.getId())
            .orElseGet(() -> TenantDatabase.builder().tenantId(tenant.getId()).build());

    tenantDatabase.setDatabaseName(provisioned.databaseName());
    tenantDatabase.setRoleName(provisioned.roleName());
    tenantDatabase.setHost(provisioned.host());
    tenantDatabase.setPort(provisioned.port());
    tenantDatabase.setSecretArn(secretArn);
    tenantDatabase.setSecretName(
        Optional.ofNullable(secret).map(ProvisionedAwsSecret::name).orElse(null));
    tenantDatabase.setSecretNamespace(
        Optional.ofNullable(secret).map(ProvisionedAwsSecret::namespace).orElse(null));
    tenantDatabase.setProvisionedAt(Instant.now());
    tenantDatabaseRepository.save(tenantDatabase);

    auditLogRepository.save(
        AuditLog.builder()
            .tenantId(tenant.getId())
            .actorUserId(user.getId())
            .eventType(AuditEventType.TENANT_DATABASE_CREATED)
            .resourceType("database")
            .resourceId(provisioned.databaseName())
            // Never the password, and never the ARN's contents - just where to look.
            .payload("{\"host\":\"" + provisioned.host() + "\"}")
            .build());

    store.advance(job.getProvisioningId(), step().next(), null);
    log.info(
        "Database {} ready for tenant {}", provisioned.databaseName(), registration.getTenantId());
  }
}
