package com.tenantos.registrar.services.database.migrations;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.PreparedStatement;
import java.util.UUID;

/**
 * Seeds the workspace every tenant starts with: one workspace ("My workspace") and one folder
 * ("default") inside it, so the UI has somewhere to land right after provisioning instead of an
 * empty workspace list.
 *
 * <p>Registered as a Java migration, like {@link V2__SeedRegistrationUser}, rather than
 * classpath-discovered, since it needs the tenant's id - registrar-side runtime data, not something
 * V3's static DDL can know. {@code workspaces.id}/{@code folders.id} have no default in V3, so both
 * UUIDs are minted here rather than left to Postgres.
 */
public class V4__SeedDefaultWorkspace extends BaseJavaMigration {

  private static final String DEFAULT_WORKSPACE_NAME = "My workspace";
  private static final String DEFAULT_WORKSPACE_SLUG = "my-workspace";
  private static final String DEFAULT_FOLDER_NAME = "default";

  private final UUID tenantId;

  public V4__SeedDefaultWorkspace(UUID tenantId) {
    this.tenantId = tenantId;
  }

  @Override
  public void migrate(Context context) throws Exception {
    UUID workspaceId = UUID.randomUUID();

    String insertWorkspace =
        """
        INSERT INTO workspaces (id, tenant_id, name, slug)
        VALUES (?, ?, ?, ?)
        """;
    try (PreparedStatement statement = context.getConnection().prepareStatement(insertWorkspace)) {
      statement.setObject(1, workspaceId);
      statement.setObject(2, tenantId);
      statement.setString(3, DEFAULT_WORKSPACE_NAME);
      statement.setString(4, DEFAULT_WORKSPACE_SLUG);
      statement.executeUpdate();
    }

    String insertFolder =
        """
        INSERT INTO folders (id, workspace_id, name)
        VALUES (?, ?, ?)
        """;
    try (PreparedStatement statement = context.getConnection().prepareStatement(insertFolder)) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, workspaceId);
      statement.setString(3, DEFAULT_FOLDER_NAME);
      statement.executeUpdate();
    }
  }
}
