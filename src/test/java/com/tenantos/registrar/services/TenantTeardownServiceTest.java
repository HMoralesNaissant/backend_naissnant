package com.tenantos.registrar.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.tenantos.registrar.entity.Tenant;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.repository.*;
import com.tenantos.registrar.services.database.TenantDatabaseProvisioner;
import com.tenantos.registrar.services.infisical.InfisicalClient;
import com.tenantos.registrar.services.kubernetes.NamespaceProvisioner;
import com.tenantos.registrar.services.tenant.TenantSlugGenerator;
import com.tenantos.registrar.services.tenant.TenantTeardownService;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

class TenantTeardownServiceTest {

  TenantRepository tenants = mock(TenantRepository.class);
  TenantsRegistrationRepository registrations = mock(TenantsRegistrationRepository.class);
  TenantWorkspaceProvisioningRepository provisioning =
      mock(TenantWorkspaceProvisioningRepository.class);
  NamespaceProvisioner namespaces = mock(NamespaceProvisioner.class);
  TenantDatabaseProvisioner databases = mock(TenantDatabaseProvisioner.class);
  InfisicalClient infisical = mock(InfisicalClient.class);
  TenantMemberRepository members = mock(TenantMemberRepository.class);
  UserRepository users = mock(UserRepository.class);
  TransactionTemplate tx = mock(TransactionTemplate.class);
  TenantTeardownService service;
  Tenant tenant = Tenant.builder().id(UUID.randomUUID()).name("Acme Inc").slug("acme-inc").build();

  @BeforeEach
  void setUp() {
    service =
        new TenantTeardownService(
            new TenantSlugGenerator(tenants),
            tenants,
            registrations,
            provisioning,
            members,
            users,
            mock(OnboardingRepository.class),
            mock(OnboardingOtpRepository.class),
            namespaces,
            databases,
            infisical,
            tx);
    ReflectionTestUtils.setField(service, "databaseEnabled", true);
    ReflectionTestUtils.setField(service, "infisicalEnabled", true);
    doAnswer(
            i -> {
              ((Consumer<?>) i.getArgument(0)).getClass();
              ((Consumer<Object>) i.getArgument(0)).accept(null);
              return null;
            })
        .when(tx)
        .executeWithoutResult(any());
    when(tenants.findBySlug("acme-inc")).thenReturn(Optional.of(tenant));
    when(registrations.findByTenantId(tenant.getId()))
        .thenReturn(List.of(TenantsRegistration.builder().companyEmail("a@acme.io").build()));
    when(namespaces.destroyWorkspace(tenant)).thenReturn(true);
  }

  @Test
  void removesEverythingStoppingTheJobFirstAndRecordsLast() {
    var result = service.destroy("Acme Inc");

    assertTrue(result.namespaceDeleted() && result.databaseDropped() && result.secretsDeleted());
    InOrder order = inOrder(provisioning, namespaces, databases, infisical, tenants);
    order.verify(provisioning).deleteByCompanyEmail("a@acme.io");
    order.verify(namespaces).destroyWorkspace(tenant);
    order.verify(databases).drop("acme-inc");
    order.verify(infisical).deleteFolder("/acme-inc");
    order.verify(tenants).delete(tenant);
  }

  @Test
  void keepsRecordsWhenAnExternalStepFails() {
    doThrow(new IllegalStateException("boom")).when(databases).drop("acme-inc");

    assertThrows(IllegalStateException.class, () -> service.destroy("acme-inc"));
    verify(tenants, never()).delete(any());
    verify(infisical, never()).deleteFolder(any());
  }

  @Test
  void unknownTenant() {
    assertThrows(NoSuchElementException.class, () -> service.destroy("ghost"));
  }
}
