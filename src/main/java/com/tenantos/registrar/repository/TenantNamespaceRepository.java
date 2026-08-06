package com.tenantos.registrar.repository;

import com.tenantos.registrar.entity.TenantNamespace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantNamespaceRepository extends JpaRepository<TenantNamespace, UUID> {

  /** Reverse lookup, for answering "which tenant owns this namespace?" from a cluster-side name. */
  Optional<TenantNamespace> findByNamespace(String namespace);
}
