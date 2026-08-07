package com.tenantos.registrar.services.database.migrations;

import com.tenantos.registrar.entity.User;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.PreparedStatement;

/**
 * Seeds the one row this tenant's database starts with: the user who registered it.
 *
 * <p>Explicitly constructed with that {@link User} and registered via Flyway's {@code
 * javaMigrations(...)} rather than classpath-discovered, since - unlike every other migration -
 * this one carries per-tenant runtime data. A {@link PreparedStatement}, not string-built SQL:
 * unlike V1's static DDL, values here (full_name especially) are user-supplied free text and must
 * never be interpolated into SQL text.
 */
public class V2__SeedRegistrationUser extends BaseJavaMigration {

  private final User user;

  public V2__SeedRegistrationUser(User user) {
    this.user = user;
  }

  @Override
  public void migrate(Context context) throws Exception {
    String sql =
        """
        INSERT INTO users (registrar_user_id, email, password_hash, first_name, status, is_platform_admin)
        VALUES (?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement statement = context.getConnection().prepareStatement(sql)) {
      statement.setObject(1, user.getId());
      statement.setString(2, user.getEmail());
      statement.setString(3, user.getPasswordHash());
      statement.setString(4, user.getFullName());
      statement.setString(5, user.getStatus().name());
      statement.setBoolean(6, true);
      statement.executeUpdate();
    }
  }
}
