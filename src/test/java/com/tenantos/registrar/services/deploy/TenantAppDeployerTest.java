package com.tenantos.registrar.services.deploy;

import com.tenantos.registrar.entity.Tenant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TenantAppDeployerTest {

  private final ForgejoWorkflowClient forgejo = mock(ForgejoWorkflowClient.class);
  private final TenantAppDeployer deployer = new TenantAppDeployer(forgejo, null);

  @Test
  void shortSlugsAreUsedVerbatim() {
    assertThat(deployer.appNameFor("acme", TenantAppDeployer.BACKEND)).isEqualTo("acme-backend");
    assertThat(deployer.appNameFor("acme", TenantAppDeployer.UI)).isEqualTo("acme-ui");
  }

  @Test
  void longSlugsStayWithinTheHostLabelBudget() {
    String slug = "a".repeat(63);

    String name = deployer.appNameFor(slug, TenantAppDeployer.BACKEND);

    // "<name>-staging" is the longest host label skaffolder builds from this name.
    assertThat(name.length() + "-staging".length()).isLessThanOrEqualTo(63);
  }

  @Test
  void longSlugsSharingAPrefixDoNotCollide() {
    String prefix = "x".repeat(60);

    assertThat(deployer.appNameFor(prefix + "-one", TenantAppDeployer.UI))
        .isNotEqualTo(deployer.appNameFor(prefix + "-two", TenantAppDeployer.UI));
  }

  @Test
  void namingIsStableAcrossCalls() {
    String slug = "y".repeat(63);

    assertThat(deployer.appNameFor(slug, TenantAppDeployer.UI))
        .isEqualTo(deployer.appNameFor(slug, TenantAppDeployer.UI));
  }

  @Test
  @SuppressWarnings("unchecked")
  void dispatchesBothAppsWithEachOthersNames() {
    ReflectionTestUtils.setField(deployer, "environment", "dev");
    ReflectionTestUtils.setField(deployer, "backendImage", "reg/backend:1");
    ReflectionTestUtils.setField(deployer, "uiImage", "reg/ui:1");
    Tenant tenant = new Tenant();
    tenant.setSlug("acme");
    tenant.setName("Acme Inc");

    deployer.requestDeploy(tenant, "tenant-acme");

    ArgumentCaptor<Map<String, String>> inputs = ArgumentCaptor.forClass(Map.class);
    verify(forgejo, times(2)).dispatch(inputs.capture());
    Map<String, String> backend = inputs.getAllValues().get(0);
    Map<String, String> ui = inputs.getAllValues().get(1);
    assertThat(backend)
        .containsEntry("app", "backend")
        .containsEntry("app_name", "acme-backend")
        .containsEntry("image", "reg/backend:1")
        .containsEntry("namespace", "tenant-acme");
    assertThat(ui)
        .containsEntry("app", "ui")
        .containsEntry("app_name", "acme-ui")
        .containsEntry("backend_name", "acme-backend")
        .containsEntry("tenant_name", "Acme Inc")
        .containsEntry("image", "reg/ui:1");
  }

  @Test
  void refusesToDispatchWithoutAnImage() {
    ReflectionTestUtils.setField(deployer, "environment", "dev");
    ReflectionTestUtils.setField(deployer, "backendImage", "reg/backend:1");
    ReflectionTestUtils.setField(deployer, "uiImage", "");
    Tenant tenant = new Tenant();
    tenant.setSlug("acme");

    assertThatThrownBy(() -> deployer.requestDeploy(tenant, "tenant-acme"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ui-image");
    // The backend is not dispatched either: a half-deployed tenant is worse than none.
    verify(forgejo, never()).dispatch(anyMap());
  }
}
