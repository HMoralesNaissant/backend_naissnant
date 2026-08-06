package com.tenantos.registrar.repository;

import com.tenantos.registrar.entity.TenantDatabase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantDatabaseRepository extends JpaRepository<TenantDatabase, UUID> {

  /** Reverse lookup, for answering "which tenant owns this database?" from a server-side name. */
  Optional<TenantDatabase> findByDatabaseName(String databaseName);
}
