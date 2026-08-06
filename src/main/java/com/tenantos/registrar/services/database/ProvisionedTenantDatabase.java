package com.tenantos.registrar.services.database;

/**
 * Everything needed to connect to a freshly provisioned tenant database, including the password -
 * which exists only for as long as it takes the caller to publish it.
 *
 * <p>The password is not recoverable afterwards: Postgres stores only its hash, and this service
 * stores nothing at all. That is why the provisioning step publishes before it records, and why a
 * retry mints a new one rather than trying to look the old one up.
 */
public record ProvisionedTenantDatabase(
    String databaseName, String roleName, String host, int port, String password) {

  /** The connection string as a tenant workload would use it. */
  public String jdbcUrl() {
    return "jdbc:postgresql://" + host + ":" + port + "/" + databaseName;
  }

  /** Keeps the password out of logs and stack traces if this record is ever printed. */
  @Override
  public String toString() {
    return "ProvisionedTenantDatabase[databaseName="
        + databaseName
        + ", roleName="
        + roleName
        + ", host="
        + host
        + ", port="
        + port
        + ", password=***]";
  }
}
