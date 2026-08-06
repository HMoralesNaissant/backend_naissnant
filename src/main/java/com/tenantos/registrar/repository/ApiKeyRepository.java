package com.tenantos.registrar.repository;

import com.tenantos.registrar.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

  /** Lookup is by hash - the plaintext key is never stored. */
  Optional<ApiKey> findByKeyHash(String keyHash);
}
