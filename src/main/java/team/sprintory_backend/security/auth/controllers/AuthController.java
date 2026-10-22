package team.sprintory_backend.security.auth.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import team.sprintory_backend.exceptionHandler.ErrorCode;
import team.sprintory_backend.exceptionHandler.ErrorResponse;
import team.sprintory_backend.security.auth.AuthCookieFactory;
import team.sprintory_backend.security.auth.dto.AuthUserResponse;
import team.sprintory_backend.security.auth.dto.ForgotPasswordRequest;
import team.sprintory_backend.security.auth.dto.LoginRequest;
import team.sprintory_backend.security.auth.dto.MessageResponse;
import team.sprintory_backend.security.auth.dto.RegisterRequest;
import team.sprintory_backend.security.auth.dto.ResetPasswordRequest;
import team.sprintory_backend.security.auth.dto.VerifyEmailRequest;
import team.sprintory_backend.security.auth.services.AuthService;
import team.sprintory_backend.security.auth.services.AuthSession;
import team.sprintory_backend.security.exceptions.InvalidTokenException;
import team.sprintory_backend.security.userdetails.AppUserDetails;

import java.time.Instant;

/** R01 HTTP contracts. Business logic belongs in AuthService (Ahmed's next step). */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthCookieFactory cookies;

    @PostMapping("/register")
    public ResponseEntity<AuthUserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthUserResponse> login(@Valid @RequestBody LoginRequest request) {
        return authenticated(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthUserResponse> refresh(
            @CookieValue(name = AuthCookieFactory.REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new InvalidTokenException("Refresh token is missing.");
        }
        return authenticated(authService.refresh(refreshToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = AuthCookieFactory.REFRESH_COOKIE, required = false) String refreshToken) {
        // Expired or missing cookies should not prevent a browser from clearing its own auth state.
        if (refreshToken != null && !refreshToken.isBlank()) {
            authService.logout(refreshToken);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clearAccess().toString())
                .header(HttpHeaders.SET_COOKIE, cookies.clearRefresh().toString())
                .build();
    }

    /** Existing React login flow fetches /auth/me immediately after receiving cookies. */
    @GetMapping("/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal AppUserDetails principal) {
        // SecurityConfig currently permits /auth/**. Return a genuine 401 without changing Ahmed's config.
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(
                    Instant.now(), HttpStatus.UNAUTHORIZED.value(), ErrorCode.AUTHENTICATION_ERROR,
                    "Authentication required.", "/api/v1/auth/me", null));
        }
        return ResponseEntity.ok(AuthUserResponse.from(principal.getUser()));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<MessageResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request.email());
        // Do not reveal whether the submitted email belongs to a user.
        return ResponseEntity.accepted().body(new MessageResponse(
                "If the account exists, password reset instructions will be sent."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        authService.verifyEmail(request.token());
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<AuthUserResponse> authenticated(AuthSession result) {
        // Raw tokens appear in HttpOnly cookies only, never in JSON.
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.access(result.accessToken(), result.rememberMe()).toString())
                .header(HttpHeaders.SET_COOKIE, cookies.refresh(result.refreshToken(), result.rememberMe()).toString())
                .body(result.user());
    }
}
