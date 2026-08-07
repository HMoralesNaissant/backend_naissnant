package com.tenantos.registrar.services.database;

import com.tenantos.registrar.entity.Tenant;
import com.tenantos.registrar.entity.User;
import com.tenantos.registrar.services.database.migrations.V2__SeedRegistrationUser;
import com.tenantos.registrar.services.database.migrations.V4__SeedDefaultWorkspace;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Service;

/**
 * Runs this tenant's own Flyway migration chain against its freshly-provisioned database: the
 * identity schema (V1, plain SQL under {@code db/tenants_migration}), the registration user seed
 * (V2, a Java migration carrying that user's data - see {@link V2__SeedRegistrationUser}), the
 * workflow resource schema (V3, plain SQL), then the default workspace seed (V4, a Java migration
 * carrying the tenant's id - see {@link V4__SeedDefaultWorkspace}).
 *
 * <p>Independent of the registrar's own Flyway instance - Spring Boot's autoconfigured bean is wired
 * to {@code spring.datasource} only. Each tenant database gets its own {@code
 * flyway_schema_history} and its own {@link Flyway} instance, constructed here per call, so a step
 * retry is a plain {@code migrate()} no-op rather than something this class has to converge by hand.
 *
 * <p>Connects with the tenant's own role, not an admin credential: {@link TenantDatabaseProvisioner}
 * makes that role the database's {@code OWNER}, so it already has every privilege this needs.
 */
@Service
@Slf4j
public class TenantSchemaMigrator {

  private static final String MIGRATIONS_LOCATION = "classpath:db/tenants_migration";

  public void migrate(ProvisionedTenantDatabase provisioned, User user, Tenant tenant) {
    Flyway flyway =
        Flyway.configure()
            .dataSource(provisioned.jdbcUrl(), provisioned.roleName(), provisioned.password())
            .locations(MIGRATIONS_LOCATION)
            .javaMigrations(
                new V2__SeedRegistrationUser(user), new V4__SeedDefaultWorkspace(tenant.getId()))
            .load();

    flyway.migrate();

    log.info(
        "Migrated database {} and seeded user {} for tenant database",
        provisioned.databaseName(),
        user.getId());
  }
}
