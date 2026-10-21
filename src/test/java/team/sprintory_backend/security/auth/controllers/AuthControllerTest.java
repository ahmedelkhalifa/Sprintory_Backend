package team.sprintory_backend.security.auth.controllers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import team.sprintory_backend.exceptionHandler.ErrorCode;
import team.sprintory_backend.exceptionHandler.ErrorResponse;
import team.sprintory_backend.security.auth.AuthCookieFactory;
import team.sprintory_backend.security.auth.dto.AuthUserResponse;
import team.sprintory_backend.security.auth.dto.ForgotPasswordRequest;
import team.sprintory_backend.security.auth.dto.LoginRequest;
import team.sprintory_backend.security.auth.dto.RegisterRequest;
import team.sprintory_backend.security.auth.dto.ResetPasswordRequest;
import team.sprintory_backend.security.auth.dto.VerifyEmailRequest;
import team.sprintory_backend.security.auth.services.AuthService;
import team.sprintory_backend.security.auth.services.AuthSession;
import team.sprintory_backend.security.exceptions.InvalidTokenException;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.user.models.User;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Pure controller tests: do not require the database or Ahmed's implementation. */
class AuthControllerTest {

    private AuthService service;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        service = mock(AuthService.class);
        controller = new AuthController(service, new AuthCookieFactory(false, 900_000L, 2_629_800_000L));
    }

    @Test
    void loginSetsTwoHttpOnlyCookiesAndNeverExposesTokensInJson() {
        AuthUserResponse user = new AuthUserResponse(7L, "Ada", "Lovelace", "ada@example.com");
        LoginRequest request = new LoginRequest("ada@example.com", "a-safe-password", true);
        when(service.login(request)).thenReturn(new AuthSession("access-secret", "refresh-secret", user));

        var response = controller.login(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(user, response.getBody());
        String jsonLikeBody = String.valueOf(response.getBody());
        assertFalse(jsonLikeBody.contains("access-secret"));
        assertFalse(jsonLikeBody.contains("refresh-secret"));
        List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookies);
        assertEquals(2, setCookies.size());
        assertTrue(setCookies.stream().anyMatch(c -> c.startsWith("access_token=access-secret;")
                && c.contains("HttpOnly") && c.contains("Path=/")));
        assertTrue(setCookies.stream().anyMatch(c -> c.startsWith("refresh_token=refresh-secret;")
                && c.contains("HttpOnly") && c.contains("Path=/api/v1/auth")));
    }

    @Test
    void registrationReturnsCreatedWithPublicUserFields() {
        RegisterRequest request = new RegisterRequest("Ada", "Lovelace", "ada@example.com", "password123");
        AuthUserResponse user = new AuthUserResponse(7L, "Ada", "Lovelace", "ada@example.com");
        when(service.register(request)).thenReturn(user);

        var response = controller.register(request);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(user, response.getBody());
    }

    @Test
    void refreshRotatesBothCookies() {
        AuthUserResponse user = new AuthUserResponse(7L, "Ada", "Lovelace", "ada@example.com");
        when(service.refresh("old-refresh")).thenReturn(new AuthSession("new-access", "new-refresh", user));

        var response = controller.refresh("old-refresh");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertNotNull(cookies);
        assertTrue(cookies.stream().anyMatch(c -> c.contains("new-access")));
        assertTrue(cookies.stream().anyMatch(c -> c.contains("new-refresh")));
    }

    @Test
    void refreshRequiresTheRefreshCookie() {
        assertThrows(InvalidTokenException.class, () -> controller.refresh(null));
        verifyNoInteractions(service);
    }

    @Test
    void logoutClearsBothCookiesEvenIfRefreshCookieIsMissing() {
        var response = controller.logout(null);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());
        List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookies);
        assertEquals(2, setCookies.size());
        assertTrue(setCookies.stream().allMatch(c -> c.contains("Max-Age=0")));
        verifyNoInteractions(service);
    }

    @Test
    void logoutWithRefreshCookieCallsServiceBeforeClearingCookies() {
        var response = controller.logout("presented-refresh-token");
        verify(service).logout("presented-refresh-token");
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    void meReturns401WhenThereIsNoAuthenticatedPrincipal() {
        var response = controller.me(null);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        ErrorResponse body = assertInstanceOf(ErrorResponse.class, response.getBody());
        assertEquals(ErrorCode.AUTHENTICATION_ERROR, body.getCode());
    }

    @Test
    void meReturnsOnlySafeProfileFields() {
        User user = User.builder().id(7L).firstName("Ada").lastName("Lovelace")
                .email("ada@example.com").password("sensitive-hash").enabled(true).build();
        var response = controller.me(new AppUserDetails(user));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        AuthUserResponse body = assertInstanceOf(AuthUserResponse.class, response.getBody());
        assertEquals("ada@example.com", body.email());
        assertEquals(7L, body.id());
        assertFalse(body.toString().contains("sensitive-hash"));
    }

    @Test
    void resetAndVerificationDelegateToServiceWithoutReturningTokens() {
        var resetResponse = controller.resetPassword(new ResetPasswordRequest("reset-token", "new-password123"));
        var verifyResponse = controller.verifyEmail(new VerifyEmailRequest("activation-token"));
        assertEquals(HttpStatus.NO_CONTENT, resetResponse.getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT, verifyResponse.getStatusCode());
        verify(service).resetPassword("reset-token", "new-password123");
        verify(service).verifyEmail("activation-token");
    }

    @Test
    void forgotPasswordHasGenericResponseAndCallsService() {
        var response = controller.forgotPassword(new ForgotPasswordRequest("missing@example.com"));
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertTrue(response.getBody().message().contains("If the account exists"));
        verify(service).forgotPassword("missing@example.com");
    }
}
