package com.tenantos.registrar.services.database;

import com.tenantos.registrar.entity.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test against the local tenant-data Postgres (compose service {@code tenant-postgres},
 * port 54323). Skipped rather than failed when that server is not reachable, so the suite stays
 * green on a machine with no containers up.
 *
 * <p>These three assertions are the ones nothing else can make. The provisioning step deliberately
 * forgets the password it generates - it goes to Secrets Manager and a Kubernetes Secret and
 * nowhere else - so an end-to-end run through the pipeline cannot check that the credential opens
 * anything. Calling the provisioner directly is the only place the password is observable.
 */
class TenantDatabaseProvisionerTest {

  private static final String ADMIN_URL = "jdbc:postgresql://localhost:54323/postgres";
  private static final String ADMIN_USER = "tenantos-tenant-admin";
  private static final String ADMIN_PASSWORD = "c4rl0sP@ssw0rds";

  private TenantDatabaseProvisioner provisioner;
  private Tenant tenant;
  private String slug;

  @BeforeEach
  void setUp() {
    assumeTrue(tenantDataServerIsUp(), "tenant-data Postgres is not running on 54323");

    provisioner = new TenantDatabaseProvisioner();
    ReflectionTestUtils.setField(provisioner, "adminUrl", ADMIN_URL);
    ReflectionTestUtils.setField(provisioner, "adminUser", ADMIN_USER);
    ReflectionTestUtils.setField(provisioner, "adminPassword", ADMIN_PASSWORD);
    ReflectionTestUtils.setField(provisioner, "tenantHost", "localhost");
    ReflectionTestUtils.setField(provisioner, "tenantPort", 54323);
    ReflectionTestUtils.setField(provisioner, "connectionLimit", 20);

    // Unique per run so a failed test cannot poison the next one.
    slug = "itest-" + UUID.randomUUID().toString().substring(0, 8);
    tenant = Tenant.builder().id(UUID.randomUUID()).name("Integration Test").slug(slug).build();
  }

  @AfterEach
  void tearDown() {
    if (slug == null || !tenantDataServerIsUp()) {
      return;
    }
    String name = "tenant_" + slug.replace('-', '_');
    try (Connection admin = adminConnection();
        Statement statement = admin.createStatement()) {
      statement.executeUpdate("DROP DATABASE IF EXISTS \"" + name + "\"");
      statement.executeUpdate("DROP ROLE IF EXISTS \"" + name + "\"");
    } catch (SQLException e) {
      // Cleanup only - a leaked test database is untidy, not a test failure.
    }
  }

  @Test
  void provision_createsADatabaseTheGeneratedPasswordCanOpen() throws SQLException {
    ProvisionedTenantDatabase provisioned = provisioner.provision(tenant);

    assertThat(provisioned.databaseName()).isEqualTo("tenant_" + slug.replace('-', '_'));
    assertThat(provisioned.roleName()).isEqualTo(provisioned.databaseName());

    try (Connection tenantConnection = connectAs(provisioned)) {
      assertThat(tenantConnection.isValid(5)).isTrue();
    }
  }

  @Test
  void provision_isIdempotent_andTheSecondPasswordSupersedesTheFirst() throws SQLException {
    ProvisionedTenantDatabase first = provisioner.provision(tenant);
    ProvisionedTenantDatabase second = provisioner.provision(tenant);

    // The convergence the retry path depends on: same database and role, new working password.
    assertThat(second.databaseName()).isEqualTo(first.databaseName());
    assertThat(second.roleName()).isEqualTo(first.roleName());
    assertThat(second.password()).isNotEqualTo(first.password());

    assertThat(countIn("SELECT count(*) FROM pg_database WHERE datname = '" + first.databaseName() + "'"))
        .isEqualTo(1);
    assertThat(countIn("SELECT count(*) FROM pg_roles WHERE rolname = '" + first.roleName() + "'"))
        .isEqualTo(1);

    try (Connection tenantConnection = connectAs(second)) {
      assertThat(tenantConnection.isValid(5)).isTrue();
    }

    // And the superseded one no longer works - ALTER ROLE replaced it rather than adding to it.
    assertThatThrownBy(() -> connectAs(first)).isInstanceOf(SQLException.class);
  }

  @Test
  void provision_deniesOneTenantAccessToAnothersDatabase() throws SQLException {
    ProvisionedTenantDatabase mine = provisioner.provision(tenant);

    String otherSlug = "itest-" + UUID.randomUUID().toString().substring(0, 8);
    Tenant other =
        Tenant.builder().id(UUID.randomUUID()).name("Other Tenant").slug(otherSlug).build();
    ProvisionedTenantDatabase theirs = provisioner.provision(other);

    try {
      // The whole point of a database per tenant, and the only thing enforcing it is
      // REVOKE ALL ON DATABASE ... FROM PUBLIC. Drop that line and this connection succeeds.
      assertThatThrownBy(
              () ->
                  DriverManager.getConnection(
                      theirs.jdbcUrl(), mine.roleName(), mine.password()))
          .isInstanceOf(SQLException.class);
    } finally {
      dropTenant("tenant_" + otherSlug.replace('-', '_'));
    }
  }

  /**
   * Documents a boundary this step does <em>not</em> draw: the server's own {@code postgres}
   * database still grants CONNECT to PUBLIC, as it does on any stock Postgres, so a tenant role can
   * open it and read the catalog - other tenants' database and role names included.
   *
   * <p>No data leaks, but the names do. Closing it is a server-level concern
   * ({@code REVOKE CONNECT ON DATABASE postgres FROM PUBLIC} at provisioning time for the cluster),
   * not something this step can do without touching the database its own admin connects through.
   * Asserting it here so the gap is recorded rather than discovered.
   */
  @Test
  void provision_doesNotRestrictTheSharedPostgresDatabase() throws SQLException {
    ProvisionedTenantDatabase provisioned = provisioner.provision(tenant);

    try (Connection catalog =
        DriverManager.getConnection(ADMIN_URL, provisioned.roleName(), provisioned.password())) {
      assertThat(catalog.isValid(5)).isTrue();
    }
  }

  @Test
  void provision_letsTheAdminReconnectAfterRevokingPublicAccess() throws SQLException {
    ProvisionedTenantDatabase provisioned = provisioner.provision(tenant);

    // revokePublicAccess takes PUBLIC's implicit CONNECT away; grantAdminAccess puts the admin
    // role's own access back explicitly, so it isn't locked out of what it just created.
    try (Connection adminConnection =
        DriverManager.getConnection(provisioned.jdbcUrl(), ADMIN_USER, ADMIN_PASSWORD)) {
      assertThat(adminConnection.isValid(5)).isTrue();
    }
  }

  @Test
  void provision_grantsTheAdminMembershipInTheTenantRole() throws SQLException {
    ProvisionedTenantDatabase provisioned = provisioner.provision(tenant);

    // CREATE DATABASE ... OWNER needs the admin to SET ROLE into the tenant role; on a real
    // (non-superuser) admin that requires explicit membership, not just the right to grant it.
    try (Connection admin = adminConnection();
        Statement statement = admin.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "SELECT pg_has_role('"
                    + ADMIN_USER
                    + "', '"
                    + provisioned.roleName()
                    + "', 'member')")) {
      resultSet.next();
      assertThat(resultSet.getBoolean(1)).isTrue();
    }
  }

  private void dropTenant(String name) {
    try (Connection admin = adminConnection();
        Statement statement = admin.createStatement()) {
      statement.executeUpdate("DROP DATABASE IF EXISTS \"" + name + "\"");
      statement.executeUpdate("DROP ROLE IF EXISTS \"" + name + "\"");
    } catch (SQLException e) {
      // Cleanup only.
    }
  }

  private Connection connectAs(ProvisionedTenantDatabase provisioned) throws SQLException {
    return DriverManager.getConnection(
        provisioned.jdbcUrl(), provisioned.roleName(), provisioned.password());
  }

  private int countIn(String sql) throws SQLException {
    try (Connection admin = adminConnection();
        Statement statement = admin.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      resultSet.next();
      return resultSet.getInt(1);
    }
  }

  private Connection adminConnection() throws SQLException {
    return DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PASSWORD);
  }

  private static boolean tenantDataServerIsUp() {
    try (Connection connection =
        DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PASSWORD)) {
      return connection.isValid(2);
    } catch (SQLException e) {
      return false;
    }
  }
}
