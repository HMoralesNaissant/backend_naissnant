package com.tenantos.registrar.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * JPA entity for the tenant_database table (V7 migration): the tenant's dedicated Postgres
 * database, on a server separate from this one. Written by the CREATE_TENANT_DATABASE step.
 *
 * <p>Keyed by tenant_id like {@link TenantSettings} and {@link TenantNamespace} - 1:1 with the
 * tenant, which is what lets the step re-run after a failure and upsert rather than duplicate.
 *
 * <p>Carries no password, by design. The credential is published to AWS Secrets Manager and to a
 * Kubernetes Secret in the tenant's namespace; the columns here only say where to find it. Reading
 * it back means fetching the Secrets Manager value at {@link #secretArn}.
 */
@Entity
@Table(name = "tenant_database")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class TenantDatabase extends BaseAuditFields {

  @Id
  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  @Column(name = "database_name", length = 63, nullable = false, unique = true)
  private String databaseName;

  /** The tenant's login role. Owns the database and is the identity in the published credential. */
  @Column(name = "role_name", length = 63, nullable = false, unique = true)
  private String roleName;

  /**
   * What a tenant workload connects to - not necessarily how the registrar reached the server to
   * create the database.
   */
  @Column(name = "host", length = 255, nullable = false)
  private String host;

  @Column(name = "port", nullable = false)
  private int port;

  /** Null when Secrets Manager publishing is disabled, as it is locally. */
  @Column(name = "secret_arn", length = 2048)
  private String secretArn;

  @Column(name = "secret_name", length = 253)
  private String secretName;

  @Column(name = "secret_namespace", length = 63)
  private String secretNamespace;

  /** Moves forward on every (re)provision; created_at pins the first. */
  @Column(name = "provisioned_at", nullable = false)
  @Default
  private Instant provisionedAt = Instant.now();
}
