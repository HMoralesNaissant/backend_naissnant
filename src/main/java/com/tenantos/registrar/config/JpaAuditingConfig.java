package com.tenantos.registrar.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables population of @CreatedDate/@LastModifiedDate fields via AuditingEntityListener.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
