package com.tenantos.registrar.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tenantos.registrar.domain.response.TenantTeardownResponse;
import com.tenantos.registrar.services.tenant.TenantTeardownService;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TenantAdminControllerTest {

  @Mock private TenantTeardownService service;
  @InjectMocks private TenantAdminController controller;

  @BeforeEach
  void enable() {
    ReflectionTestUtils.setField(controller, "adminToken", "s3cret");
  }

  @Test
  void disabledWhenNoTokenConfigured() {
    ReflectionTestUtils.setField(controller, "adminToken", "");
    assertEquals(HttpStatus.NOT_FOUND, controller.destroyTenant("", "acme").getStatusCode());
    verifyNoInteractions(service);
  }

  @Test
  void rejectsMissingOrWrongToken() {
    assertEquals(HttpStatus.UNAUTHORIZED, controller.destroyTenant(null, "acme").getStatusCode());
    assertEquals(HttpStatus.UNAUTHORIZED, controller.destroyTenant("nope", "acme").getStatusCode());
    verify(service, never()).destroy("acme");
  }

  @Test
  void destroysWithValidToken() {
    var result = new TenantTeardownResponse("acme", true, true, true, true);
    when(service.destroy("Acme")).thenReturn(result);
    var response = controller.destroyTenant("s3cret", "Acme");
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(result, response.getBody());
  }

  @Test
  void unknownTenantIs404() {
    when(service.destroy("ghost")).thenThrow(new NoSuchElementException("No tenant with slug ghost"));
    assertEquals(HttpStatus.NOT_FOUND, controller.destroyTenant("s3cret", "ghost").getStatusCode());
  }
}
