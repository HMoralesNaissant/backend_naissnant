package com.tenantos.registrar.services.database;

import com.tenantos.registrar.entity.Tenant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Creates a tenant's Postgres database and login role on the tenant-data server.
 *
 * <p>Not under {@code services/aws}: this is plain Postgres, and the same statements work against
 * RDS, Aurora, or the local container. The server it targets is configured separately from
 * {@code spring.datasource} on purpose - tenant data and the control plane belong in different
 * blast radii.
 *
 * <p><b>Why its own JDBC connection rather than the JPA one.</b> {@code CREATE DATABASE} cannot run
 * inside a transaction block, so this cannot ride any Spring-managed transaction, and it is a
 * different server besides. A handful of statements per tenant does not justify a second pooled
 * DataSource, so it opens a connection per call and closes it.
 *
 * <p><b>Convergence, not skipping.</b> A role's password cannot be read back once set, so a retry
 * has no way to recover what the previous attempt generated. Every call therefore mints a fresh
 * password and ALTERs the role if it already exists. Whatever a crash left behind, the next call
 * ends at the same known state - which is what the pipeline's non-transactional step contract asks
 * for, since it must be idempotent by hand.
 */
@Service
@Slf4j
public class TenantDatabaseProvisioner {

  private static final String NAME_PREFIX = "tenant_";
  private static final int MAX_IDENTIFIER_LENGTH = 63; // Postgres NAMEDATALEN - 1
  private static final int PASSWORD_LENGTH = 40;

  /**
   * Alphanumeric only, and that is a security decision rather than a lazy one. The password is
   * interpolated into {@code CREATE ROLE ... PASSWORD '...'}, which is DDL and cannot be
   * parameterised; excluding quotes and backslashes removes the escaping hazard rather than
   * managing it. 40 chars of this alphabet is ~238 bits.
   */
  private static final String PASSWORD_ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

  /**
   * What may be interpolated as an identifier. Slugs are already normalised upstream by
   * TenantSlugGenerator, so this is defence in depth - but it is the only thing standing between a
   * future change in slug generation and SQL injection, since JDBC cannot parameterise identifiers.
   */
  private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

  @Value("${tenant.database.admin-url:}")
  private String adminUrl;

  @Value("${tenant.database.admin-user:}")
  private String adminUser;

  @Value("${tenant.database.admin-password:}")
  private String adminPassword;

  @Value("${tenant.database.host:localhost}")
  private String tenantHost;

  @Value("${tenant.database.port:54323}")
  private int tenantPort;

  @Value("${tenant.database.connection-limit:20}")
  private int connectionLimit;

  private final SecureRandom secureRandom = new SecureRandom();

  /**
   * Creates (or converges onto) the tenant's database, role and grants, and returns the credential.
   *
   * <p>The returned password is live and unrecoverable - publish it before doing anything that can
   * fail, or the tenant ends up with a database nobody can open until the next retry.
   */
  public ProvisionedTenantDatabase provision(Tenant tenant) {
    String databaseName = databaseNameFor(tenant.getSlug());
    String roleName = roleNameFor(tenant.getSlug());
    String password = generatePassword();

    try (Connection connection =
        DriverManager.getConnection(adminUrl, adminUser, adminPassword)) {
      // CREATE DATABASE refuses to run in a transaction block; autocommit is not optional here.
      connection.setAutoCommit(true);

      upsertRole(connection, roleName, password);
      createDatabaseIfAbsent(connection, databaseName, roleName);
      revokePublicAccess(connection, databaseName);
    } catch (SQLException e) {
      throw new IllegalStateException(
          "Failed to provision database " + databaseName + " for tenant " + tenant.getId(), e);
    }

    log.info(
        "Provisioned database {} owned by role {} for tenant {}",
        databaseName,
        roleName,
        tenant.getId());

    return new ProvisionedTenantDatabase(databaseName, roleName, tenantHost, tenantPort, password);
  }

  /**
   * The database name a tenant's slug maps to. Public so a caller can derive it without connecting,
   * and so this stays the single definition of the rule - the same reasoning as
   * {@code EksNamespaceProvisioner.namespaceNameFor}.
   *
   * <p>Hyphens become underscores: a slug is a valid DNS label and may contain hyphens, but a
   * Postgres identifier containing one has to be double-quoted at every single use site. Trading
   * that for an underscore once here is cheaper than remembering to quote forever.
   */
  public String databaseNameFor(String slug) {
    return identifierFor(slug);
  }

  /** The tenant's login role. Same name as the database - they are 1:1 and it aids diagnosis. */
  public String roleNameFor(String slug) {
    return identifierFor(slug);
  }

  private String identifierFor(String slug) {
    String identifier = (NAME_PREFIX + slug).toLowerCase(Locale.ROOT).replace('-', '_');
    if (identifier.length() > MAX_IDENTIFIER_LENGTH) {
      identifier = identifier.substring(0, MAX_IDENTIFIER_LENGTH);
    }
    return requireSafeIdentifier(identifier);
  }

  private String requireSafeIdentifier(String identifier) {
    if (!SAFE_IDENTIFIER.matcher(identifier).matches()) {
      throw new IllegalArgumentException(
          "Refusing to use \"" + identifier + "\" as a Postgres identifier");
    }
    return identifier;
  }

  /**
   * Creates the role, or resets its password if a previous attempt already created it. The reset is
   * the convergence point the whole retry story rests on: it is the only way to arrive at a known
   * password when the stored one cannot be read back.
   */
  private void upsertRole(Connection connection, String roleName, String password)
      throws SQLException {
    boolean exists = exists(connection, "SELECT 1 FROM pg_roles WHERE rolname = ?", roleName);

    // Identifiers are interpolated because JDBC cannot bind them; both have passed
    // requireSafeIdentifier, and the password's alphabet excludes quotes by construction.
    try (Statement statement = connection.createStatement()) {
      if (exists) {
        log.info("Role {} already exists, resetting its password", roleName);
        statement.executeUpdate(
            "ALTER ROLE \"" + roleName + "\" WITH LOGIN PASSWORD '" + password + "'");
      } else {
        statement.executeUpdate(
            "CREATE ROLE \""
                + roleName
                + "\" WITH LOGIN PASSWORD '"
                + password
                + "' CONNECTION LIMIT "
                + connectionLimit);
      }
    }
  }

  /** Postgres has no CREATE DATABASE IF NOT EXISTS, hence the catalog check. */
  private void createDatabaseIfAbsent(Connection connection, String databaseName, String roleName)
      throws SQLException {
    if (exists(connection, "SELECT 1 FROM pg_database WHERE datname = ?", databaseName)) {
      log.info("Database {} already exists, leaving it in place", databaseName);
      return;
    }
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "CREATE DATABASE \"" + databaseName + "\" OWNER \"" + roleName + "\"");
    }
  }

  /**
   * Without this every role on the server can connect to the new database, which would make
   * "a database per tenant" an organisational convention rather than a boundary. It is the single
   * line stopping one tenant from opening another's database.
   *
   * <p>What it does <em>not</em> cover: the server's own {@code postgres} database still grants
   * CONNECT to PUBLIC, as on any stock Postgres, so a tenant role can open it and read the catalog -
   * including other tenants' database and role names. No data is exposed, but the names are.
   * Closing that means {@code REVOKE CONNECT ON DATABASE postgres FROM PUBLIC} when the cluster is
   * built; it is not done here because this is the database the admin connects through.
   */
  private void revokePublicAccess(Connection connection, String databaseName) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("REVOKE ALL ON DATABASE \"" + databaseName + "\" FROM PUBLIC");
    }
  }

  private boolean exists(Connection connection, String sql, String parameter) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, parameter);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next();
      }
    }
  }

  private String generatePassword() {
    StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
    for (int i = 0; i < PASSWORD_LENGTH; i++) {
      sb.append(PASSWORD_ALPHABET.charAt(secureRandom.nextInt(PASSWORD_ALPHABET.length())));
    }
    return sb.toString();
  }
}
