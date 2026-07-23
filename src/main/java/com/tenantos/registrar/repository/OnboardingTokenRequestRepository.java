package com.tenantos.registrar.repository;

import com.tenantos.registrar.entity.OnboardingTokenRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OnboardingTokenRequestRepository extends JpaRepository<OnboardingTokenRequest, String> {
}
