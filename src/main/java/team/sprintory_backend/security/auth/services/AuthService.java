package team.sprintory_backend.security.auth.services;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import team.sprintory_backend.security.auth.dto.AuthUserResponse;
import team.sprintory_backend.security.auth.dto.LoginRequest;
import team.sprintory_backend.security.auth.dto.RegisterRequest;

/**
 * R01 business-logic handoff for Ahmed.
 *
 * This is deliberately a Spring bean so the application can start and the controllers
 * can be integrated before registration/login/recovery services are implemented.
 * Every unfinished operation fails explicitly (HTTP 501); never fake an authenticated session.
 */
@Service
public class AuthService {

    /** TODO: normalize email, hash password, provision a non-admin LOCAL user and send verification. */
    public AuthUserResponse register(RegisterRequest request) {
        throw pending();
    }

    /** TODO: authenticate with Spring AuthenticationManager, then issue access/refresh tokens. */
    public AuthSession login(LoginRequest request) {
        throw pending();
    }

    /** TODO: consume a refresh token atomically and issue a replacement plus access token. */
    public AuthSession refresh(String rawRefreshToken) {
        throw pending();
    }

    /** TODO: revoke the presented session's refresh token; policy for all devices needs agreement. */
    public void logout(String rawRefreshToken) {
        throw pending();
    }

    /** TODO: send recovery instructions without revealing whether the email exists. */
    public void forgotPassword(String email) {
        throw pending();
    }

    /** TODO: validate single-use expiring reset token, securely hash password, revoke sessions. */
    public void resetPassword(String token, String newPassword) {
        throw pending();
    }

    /** TODO: validate single-use email-verification token and activate the LOCAL account. */
    public void verifyEmail(String token) {
        throw pending();
    }

    private static ResponseStatusException pending() {
        return new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                "Authentication business logic has not been implemented yet.");
    }
}
