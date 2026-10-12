package com.tenantos.registrar.services;

import com.tenantos.registrar.domain.response.AccountNameAvailabilityResponse;
import com.tenantos.registrar.repository.TenantRepository;
import com.tenantos.registrar.services.onboarding.AccountNameAvailabilityService;
import com.tenantos.registrar.services.tenant.TenantSlugGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountNameAvailabilityServiceTest {

  private final TenantRepository tenantRepository = mock(TenantRepository.class);
  // The real generator: availability must use exactly the normalization registration applies.
  private final TenantSlugGenerator slugGenerator = new TenantSlugGenerator(tenantRepository);
  private final AccountNameAvailabilityService service =
      new AccountNameAvailabilityService(tenantRepository, slugGenerator);

  @Test
  void freeName_isAvailable_withNoSuggestion() {
    AccountNameAvailabilityResponse result = service.check("Acme Inc");

    assertThat(result.slug()).isEqualTo("acme-inc");
    assertThat(result.available()).isTrue();
    assertThat(result.suggestion()).isNull();
  }

  @Test
  void takenSlug_isUnavailable_andSuggestsTheNextFreeOne() {
    when(tenantRepository.existsBySlug("acme")).thenReturn(true);

    AccountNameAvailabilityResponse result = service.check("Acme");

    assertThat(result.available()).isFalse();
    assertThat(result.suggestion()).isEqualTo("acme-2");
  }

  @Test
  void suggestionSkipsSlugsThatAreAlsoTaken() {
    when(tenantRepository.existsBySlug("acme")).thenReturn(true);
    when(tenantRepository.existsBySlug("acme-2")).thenReturn(true);

    assertThat(service.check("Acme").suggestion()).isEqualTo("acme-3");
  }

  @Test
  void namesDifferingOnlyByCaseAndPunctuation_shareAnAnswer() {
    when(tenantRepository.existsBySlug("acme-inc")).thenReturn(true);

    assertThat(service.check("ACME  Inc.").available()).isFalse();
    assertThat(service.check("acme-inc").available()).isFalse();
  }

  @Test
  void surroundingWhitespaceIsIgnored() {
    assertThat(service.check("  Acme  ").slug()).isEqualTo("acme");
  }

  @Test
  void blankOrMissingName_isRejected() {
    assertThatThrownBy(() -> service.check(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.check("   ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nameWithNoLettersOrDigits_isRejected() {
    assertThatThrownBy(() -> service.check("!!! ???"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("letter or digit");
  }

  @Test
  void nameOverTheColumnLimit_isRejected() {
    assertThatThrownBy(() -> service.check("a".repeat(101)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("100");
    assertThat(service.check("a".repeat(100)).available()).isTrue();
  }
}
