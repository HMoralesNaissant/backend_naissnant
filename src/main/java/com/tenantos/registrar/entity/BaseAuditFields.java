package com.tenantos.registrar.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class BaseAuditFields {

    @Column(name = "created_at", nullable = false)
    @CreatedDate
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    @LastModifiedDate
    private Instant updatedAt;
}
