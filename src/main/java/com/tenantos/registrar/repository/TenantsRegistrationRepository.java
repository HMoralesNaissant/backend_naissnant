package com.tenantos.registrar.repository;

import com.tenantos.registrar.entity.TenantsRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TenantsRegistrationRepository extends JpaRepository<TenantsRegistration, String> {
}
