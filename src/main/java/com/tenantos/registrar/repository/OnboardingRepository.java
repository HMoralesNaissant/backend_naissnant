package com.tenantos.registrar.repository;

import com.tenantos.registrar.entity.Onboarding;
import com.tenantos.registrar.enums.OnboardingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OnboardingRepository extends JpaRepository<Onboarding, String> {
  // repository methods can be added later if needed

  @Query("SELECT o FROM Onboarding o WHERE o.companyEmail = :companyEmail AND o.status = PENDING")
  Optional<Onboarding> findByIdAndPending(String companyEmail);

  @Query("SELECT o FROM Onboarding o WHERE o.companyEmail = :companyEmail AND o.status = :status")
  Optional<Onboarding> findByIdAndStatus(String companyEmail, OnboardingStatus status);
}
