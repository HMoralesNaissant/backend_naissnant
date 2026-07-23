package com.tenantos.registrar.repository;

import com.tenantos.registrar.entity.Onboarding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OnboardingRepository extends JpaRepository<Onboarding, String> {
    // repository methods can be added later if needed
}

