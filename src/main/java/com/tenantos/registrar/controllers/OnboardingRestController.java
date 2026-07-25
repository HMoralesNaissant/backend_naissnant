package com.tenantos.registrar.controllers;

import com.tenantos.registrar.domain.request.AccountRegistrationRequest;
import com.tenantos.registrar.domain.request.OnboardingRequest;
import com.tenantos.registrar.domain.request.OtpValidationRequest;
import com.tenantos.registrar.domain.request.ResendCodeRequest;
import com.tenantos.registrar.domain.response.AccountRegistrationResponse;
import com.tenantos.registrar.domain.response.ValidateOtpResponse;
import com.tenantos.registrar.entity.Onboarding;
import com.tenantos.registrar.entity.TenantsRegistration;
import com.tenantos.registrar.services.OnboardingOnFlightTokenService;
import com.tenantos.registrar.services.OnboardingService;
import com.tenantos.registrar.services.OnboardingOtpService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/onboarding")
@RequiredArgsConstructor
public class OnboardingRestController {

  private final OnboardingService onboardingService;
  private final OnboardingOtpService onboardingTokenService;
  private final OnboardingOnFlightTokenService onboardingOnFlightTokenService;

  @Operation(
      summary = "Issue a preflight onboarding token",
      description =
          "Call once when the onboarding page loads — this is the first request a fresh "
              + "client makes. Sets a short-lived httpOnly cookie (SameSite=Lax) that "
              + "authenticates every /onboarding/** call for the rest of the funnel (create, "
              + "validate, register). CSRF protection is disabled for this service; the cookie "
              + "itself is the auth mechanism.")
  @GetMapping("/token")
  public ResponseEntity<?> issueToken(HttpServletRequest request, HttpServletResponse response) {
    Map<String, Object> userClientDetails = this.getUserClientDetails(request);
    String rawToken = onboardingOnFlightTokenService.issue(userClientDetails);
    long ttlSeconds = onboardingOnFlightTokenService.getTtlSeconds();

    ResponseCookie cookie =
        ResponseCookie.from(onboardingOnFlightTokenService.getOnboardingSessionTokenCookieName(), rawToken)
            .httpOnly(true)
            .secure(request.isSecure())
            .sameSite("Lax")
            .path("/onboarding")
            .maxAge(ttlSeconds)
            .build();

    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

    return ResponseEntity.ok(Map.of("expiresInSeconds", ttlSeconds));
  }

  @Operation(
      summary = "Create onboarding event",
      description =
          "Creates an onboarding record for a company, "
              + "emails an OTP verification code, and never returns it. Ensures uniqueness per company_email. "
              + "Requires a valid onboarding_token cookie from GET /onboarding/token.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "201",
            description = "Created",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = Onboarding.class))),
        @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden - missing, invalid, or expired onboarding token",
            content = @Content),
        @ApiResponse(
            responseCode = "409",
            description = "Conflict - onboarding already exists for company_email",
            content = @Content),
        @ApiResponse(
            responseCode = "500",
            description = "Internal Server Error",
            content = @Content)
      })
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      description = "Onboarding request payload",
      required = true,
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = OnboardingRequest.class)))
  @PostMapping
  public ResponseEntity<?> onboardUser(@Valid @RequestBody OnboardingRequest request) {
    Onboarding entity =
        Onboarding.builder()
            .companyEmail(request.companyEmail())
            .otpType(request.otpType() != null ? request.otpType() : "code")
            .build();

    Onboarding saved = onboardingService.onboardUser(entity);
    return ResponseEntity.status(HttpStatus.CREATED).body(saved);
  }



  @Operation(
      summary = "Validate the emailed OTP code",
      description =
          "Checks the code against the "
              + "onboarding record identified by companyEmail, matching {type} against the record's otpType. "
              + "On success, the onboarding record becomes ready for account registration.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Verified", content = @Content),
        @ApiResponse(
            responseCode = "400",
            description = "Bad Request - invalid/expired/mismatched code",
            content = @Content),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden - missing, invalid, or expired onboarding token",
            content = @Content)
      })
  @PostMapping("/{type}/validate")
  public ResponseEntity<ValidateOtpResponse> validateOtp(
      @PathVariable String type, @Valid @RequestBody OtpValidationRequest request) {
    return ResponseEntity.ok(
        onboardingTokenService.validateOtp(
            new OnboardingOtpService.OtpValidationCommand(
                request.companyEmail(), type, request.code())));
  }

  @Operation(
      summary = "Resend the OTP code",
      description =
          "Invalidates any previously issued OTP code and emails a new one for the "
              + "onboarding record identified by companyEmail, matching {type} against the "
              + "record's otpType.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Resent", content = @Content),
        @ApiResponse(
            responseCode = "400",
            description = "Bad Request - no onboarding record for company_email, or otpType mismatch",
            content = @Content),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden - missing, invalid, or expired onboarding token",
            content = @Content),
        @ApiResponse(
            responseCode = "409",
            description = "Conflict - already registered for company_email",
            content = @Content)
      })
  @PostMapping("/{type}/resend")
  public ResponseEntity<?> resendCode(
      @PathVariable String type, @Valid @RequestBody ResendCodeRequest request) {
    onboardingTokenService.resendCode(
        new OnboardingOtpService.ResendCodeCommand(request.companyEmail(), type));
    return ResponseEntity.ok().build();
  }

  @Operation(
      summary = "Complete tenant account registration",
      description =
          "Creates the tenants_registration "
              + "row for a company_email whose OTP has already been validated. This is the final step of the "
              + "onboarding funnel - the preflight/session token is consumed here.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "201",
            description = "Created",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = AccountRegistrationResponse.class))),
        @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden - missing, invalid, or expired onboarding token",
            content = @Content),
        @ApiResponse(
            responseCode = "409",
            description = "Conflict - not yet OTP-verified, or already registered",
            content = @Content)
      })
  @PostMapping("/account-registration")
  public ResponseEntity<?> registerAccount(
      @Valid @RequestBody AccountRegistrationRequest request, HttpServletRequest servletRequest) {
    TenantsRegistration saved =
        onboardingService.register(
            new OnboardingService.RegistrationCommand(
                request.vrfkToken(),
                request.companyEmail(),
                request.fullName(),
                request.password(),
                request.accountName()));

    // Funnel's single-use burn point - only once registration actually succeeds.
    // Burning it earlier (e.g. before calling the service) would mean a failed
    // attempt - wrong state, already registered - permanently locks the user out:
    // the onboarding row already exists so a fresh GET /onboarding/token + POST
    // /onboarding can't recreate it, and the old cookie would already be dead.
    // TenantsRegistrationRepository's unique company_email already guards against a
    // genuine double-submit race creating two rows, so consuming late costs nothing.
    String onboardingToken = onboardingOnFlightTokenService.extractToken(servletRequest);
    onboardingOnFlightTokenService.validateAndConsume(onboardingToken);

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new AccountRegistrationResponse(
                saved.getCompanyEmail(),
                saved.getFullName(),
                saved.getAccountName(),
                saved.getStatus()));
  }

  private Map<String, Object> getUserClientDetails(HttpServletRequest request) {
    Map<String, Object> clientDetails = new LinkedHashMap<>();
    String ipAddress = request.getRemoteAddr();
    String userAgent = request.getHeader("User-Agent");
    String acceptLanguage = request.getHeader("Accept-Language");

    if (ipAddress != null) clientDetails.put("ipAddress", ipAddress);
    if (userAgent != null) clientDetails.put("userAgent", userAgent);
    if (acceptLanguage != null) clientDetails.put("acceptLanguage", acceptLanguage);
    return clientDetails;
  }
}
