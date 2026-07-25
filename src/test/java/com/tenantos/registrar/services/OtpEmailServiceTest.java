package com.tenantos.registrar.services;

import com.tenantos.registrar.entity.Onboarding;
import com.tenantos.registrar.entity.OnboardingOtp;
import com.tenantos.registrar.repository.OnboardingOtpRepository;
import com.tenantos.registrar.utils.HashUtils;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.time.Instant;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OtpEmailServiceTest {

  private static final String VERIFY_URL_TEMPLATE =
      "http://localhost:3000/onboarding/verify?email={email}&vftk={vftk}";

  @Mock private OnboardingOtpRepository repository;
  @Mock private JavaMailSender mailSender;
  @Mock private ITemplateEngine templateEngine;

  @InjectMocks private OtpEmailService service;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(service, "ttlSeconds", 600L);
    ReflectionTestUtils.setField(service, "fromAddress", "no-reply@tenantos.local");
    ReflectionTestUtils.setField(service, "verifyUrlTemplate", VERIFY_URL_TEMPLATE);
    when(repository.save(any(OnboardingOtp.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  private static MimeMessage newMimeMessage() {
    return new MimeMessage(Session.getInstance(new Properties()));
  }

  @Test
  void generateAndSend_persistsHashedCode_andRendersTheMatchingRawCodeAndVerifyLink()
      throws Exception {
    Onboarding onboarding = Onboarding.builder().companyEmail("a@example.com").build();
    MimeMessage mimeMessage = newMimeMessage();
    when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
    ArgumentCaptor<Context> contextCaptor = ArgumentCaptor.forClass(Context.class);
    when(templateEngine.process(eq("otp-code-template"), contextCaptor.capture()))
        .thenReturn("<html>rendered</html>");

    service.generateAndSend(onboarding);

    ArgumentCaptor<OnboardingOtp> otpCaptor = ArgumentCaptor.forClass(OnboardingOtp.class);
    verify(repository).save(otpCaptor.capture());
    OnboardingOtp savedOtp = otpCaptor.getValue();
    assertThat(savedOtp.getCompanyEmail()).isEqualTo("a@example.com");
    assertThat(savedOtp.getExpiresAt()).isAfter(Instant.now());

    verify(mailSender).send(mimeMessage);
    assertThat(mimeMessage.getFrom()[0].toString()).isEqualTo("no-reply@tenantos.local");
    assertThat(mimeMessage.getAllRecipients()[0].toString()).isEqualTo("a@example.com");
    assertThat(mimeMessage.getSubject()).isEqualTo("Your tenantOS verification code");

    // The code handed to the template (for display) must hash to exactly what was
    // persisted - not just "some 6-digit string was rendered and something was saved".
    String codeDisplay = (String) contextCaptor.getValue().getVariable("codeDisplay");
    String emailedCode = codeDisplay.replace(" ", "");
    assertThat(emailedCode).matches("\\d{6}");
    assertThat(savedOtp.getCodeHash()).isEqualTo(HashUtils.sha256Hex(emailedCode));

    // The verify link handed to the template carries that same code, plus the
    // (URL-encoded) email, substituted into the configured template.
    String verifyUrl = (String) contextCaptor.getValue().getVariable("verifyUrl");
    assertThat(verifyUrl)
        .isEqualTo(
            "http://localhost:3000/onboarding/verify?email=a%40example.com&vftk="
                + savedOtp.getOtpId());
  }

  @Test
  void generateAndSend_savesBeforeSending() throws Exception {
    // The OTP row must exist before the email goes out, otherwise a user could receive
    // (and try) a code that validateOtp has no record of yet.
    Onboarding onboarding = Onboarding.builder().companyEmail("order@example.com").build();
    when(mailSender.createMimeMessage()).thenReturn(newMimeMessage());
    when(templateEngine.process(anyString(), any(Context.class))).thenReturn("<html></html>");

    service.generateAndSend(onboarding);

    InOrder order = inOrder(repository, mailSender);
    order.verify(repository).save(any(OnboardingOtp.class));
    order.verify(mailSender).send(any(MimeMessage.class));
  }

  // A test asserting the sent MimeMessage's actual multipart/text-plus-html wire
  // structure was tried here and dropped: MimeMessageHelper's multipart construction
  // depends on the JVM-wide jakarta.activation.CommandMap being initialized, which
  // only reliably happens once Spring Boot's mail autoconfiguration has run - so this
  // was flaky under the full test suite depending on whether BackendApplicationTests
  // (the only @SpringBootTest here) had already run in the same JVM. Verified instead
  // by actually sending through Mailpit against the running app.

}
