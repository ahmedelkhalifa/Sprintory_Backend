package team.sprintory_backend.security.auth.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import team.sprintory_backend.notification.email.EmailDeliveryException;
import team.sprintory_backend.notification.email.EmailService;
import team.sprintory_backend.security.auth.dto.AuthUserResponse;
import team.sprintory_backend.security.auth.dto.LoginRequest;
import team.sprintory_backend.security.auth.dto.RegisterRequest;
import team.sprintory_backend.security.auth.models.RefreshToken;
import team.sprintory_backend.security.exceptions.EmailAlreadyRegisteredException;
import team.sprintory_backend.security.exceptions.InvalidTokenException;
import team.sprintory_backend.security.exceptions.ProviderMismatchException;
import team.sprintory_backend.security.jwt.JwtService;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.user.models.Provider;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static team.sprintory_backend.security.auth.models.OneTimeTokenPurpose.RESET_PASSWORD;
import static team.sprintory_backend.security.auth.models.OneTimeTokenPurpose.VERIFY_EMAIL;

/** Pure service tests: every collaborator is mocked, so no database, Spring context or SMTP. */
class AuthServiceTest {

    private UserRepository userRepository;
    private AuthenticationManager authenticationManager;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private RefreshTokenService refreshTokenService;
    private OneTimeTokenService oneTimeTokenService;
    private EmailService emailService;
    private AuthService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        authenticationManager = mock(AuthenticationManager.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        oneTimeTokenService = mock(OneTimeTokenService.class);
        emailService = mock(EmailService.class);
        service = new AuthService(userRepository, authenticationManager, passwordEncoder, jwtService,
                refreshTokenService, oneTimeTokenService, emailService);
        ReflectionTestUtils.setField(service, "frontendUrl", "http://front");
        ReflectionTestUtils.setField(service, "verifyEmailPath", "/verify-email");
        ReflectionTestUtils.setField(service, "resetPasswordPath", "/reset-password");
        when(passwordEncoder.encode(anyString())).thenAnswer(call -> "hashed:" + call.getArgument(0));
    }

    // --- login ---

    @Test
    void loginRefusesGoogleAccountBeforeCheckingThePassword() {
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(googleUser()));

        assertThrows(ProviderMismatchException.class,
                () -> service.login(new LoginRequest("ada@example.com", "password123", true)));
        verifyNoInteractions(authenticationManager);
    }

    @Test
    void loginNormalizesEmailAndIssuesSessionWithRememberMe() {
        User user = localUser(true);
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(authenticationManager.authenticate(any())).thenReturn(
                UsernamePasswordAuthenticationToken.authenticated(new AppUserDetails(user), null, List.of()));
        when(jwtService.generateAccessToken("ada@example.com")).thenReturn("access");
        when(refreshTokenService.issue(user, false)).thenReturn("refresh");

        AuthSession session = service.login(new LoginRequest("  Ada@Example.COM ", "password123", false));

        ArgumentCaptor<Authentication> attempt = ArgumentCaptor.forClass(Authentication.class);
        verify(authenticationManager).authenticate(attempt.capture());
        assertEquals("ada@example.com", attempt.getValue().getName());
        assertEquals("access", session.accessToken());
        assertEquals("refresh", session.refreshToken());
        assertFalse(session.rememberMe());
        assertEquals(7L, session.user().id());
    }

    // --- register ---

    @Test
    void registerCreatesUnverifiedMemberAndEmailsVerificationLink() {
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.empty());
        when(oneTimeTokenService.issue(any(User.class), eq(VERIFY_EMAIL))).thenReturn("tok");

        AuthUserResponse response = service.register(
                new RegisterRequest(" Ada ", "Lovelace", "Ada@Example.com", "password123"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        User user = saved.getValue();
        assertEquals("ada@example.com", user.getEmail());
        assertEquals("Ada", user.getFirstName());
        assertEquals(Provider.LOCAL, user.getProvider());
        assertFalse(user.isEnabled());
        assertEquals("hashed:password123", user.getPassword());
        verify(emailService).sendEmailVerification("ada@example.com", "Ada", "http://front/verify-email?token=tok");
        assertEquals("ada@example.com", response.email());
    }

    @Test
    void registerRejectsAnEmailThatIsAlreadyVerified() {
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(localUser(true)));

        assertThrows(EmailAlreadyRegisteredException.class, () -> service.register(registerRequest()));
        verify(userRepository, never()).saveAndFlush(any());
        verifyNoInteractions(oneTimeTokenService, emailService);
    }

    @Test
    void registerRejectsAnEmailThatBelongsToGoogle() {
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(googleUser()));

        assertThrows(ProviderMismatchException.class, () -> service.register(registerRequest()));
        verifyNoInteractions(oneTimeTokenService, emailService);
    }

    @Test
    void registerAgainWhileUnverifiedUpdatesTheAccountAndSendsAFreshLink() {
        User existing = localUser(false);
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(existing));
        when(oneTimeTokenService.issue(existing, VERIFY_EMAIL)).thenReturn("fresh");

        service.register(new RegisterRequest("Augusta", "King", "ada@example.com", "newpassword1"));

        verify(userRepository).saveAndFlush(existing);
        assertEquals("Augusta", existing.getFirstName());
        assertEquals("King", existing.getLastName());
        assertEquals("hashed:newpassword1", existing.getPassword());
        verify(emailService).sendEmailVerification("ada@example.com", "Augusta", "http://front/verify-email?token=fresh");
    }

    @Test
    void registerPropagatesEmailFailureSoTheTransactionRollsBack() {
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.empty());
        when(oneTimeTokenService.issue(any(User.class), eq(VERIFY_EMAIL))).thenReturn("tok");
        doThrow(new EmailDeliveryException("down"))
                .when(emailService).sendEmailVerification(anyString(), anyString(), anyString());

        assertThrows(EmailDeliveryException.class, () -> service.register(registerRequest()));
    }

    // --- verify email ---

    @Test
    void verifyEmailEnablesTheAccount() {
        User user = localUser(false);
        when(oneTimeTokenService.consume("tok", VERIFY_EMAIL)).thenReturn(user);

        service.verifyEmail("tok");

        assertTrue(user.isEnabled());
        verify(userRepository).save(user);
    }

    // --- refresh and logout ---

    @Test
    void refreshKeepsTheRememberMeChoiceOfTheConsumedToken() {
        User user = localUser(true);
        when(refreshTokenService.verifyAndConsume("old")).thenReturn(refreshToken(user, false));
        when(jwtService.generateAccessToken("ada@example.com")).thenReturn("new-access");
        when(refreshTokenService.issue(user, false)).thenReturn("new-refresh");

        AuthSession session = service.refresh("old");

        assertEquals("new-access", session.accessToken());
        assertEquals("new-refresh", session.refreshToken());
        assertFalse(session.rememberMe());
    }

    @Test
    void refreshRejectsADisabledUser() {
        when(refreshTokenService.verifyAndConsume("old")).thenReturn(refreshToken(localUser(false), true));

        assertThrows(InvalidTokenException.class, () -> service.refresh("old"));
        verify(refreshTokenService, never()).issue(any(), anyBoolean());
    }

    @Test
    void logoutRevokesEveryTokenOfThePresentedTokensOwner() {
        service.logout("raw");

        verify(refreshTokenService).revokeAllForToken("raw");
    }

    // --- forgot and reset password ---

    @Test
    void forgotPasswordEmailsAResetLinkToAVerifiedPasswordAccount() {
        User user = localUser(true);
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(oneTimeTokenService.issue(user, RESET_PASSWORD)).thenReturn("reset");

        service.forgotPassword(" Ada@Example.com ");

        verify(emailService).sendPasswordReset("ada@example.com", "Ada", "http://front/reset-password?token=reset");
    }

    @Test
    void forgotPasswordSendsNothingForUnknownGoogleOrUnverifiedAccounts() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("google@example.com")).thenReturn(Optional.of(googleUser()));
        when(userRepository.findByEmail("pending@example.com")).thenReturn(Optional.of(localUser(false)));

        service.forgotPassword("nobody@example.com");
        service.forgotPassword("google@example.com");
        service.forgotPassword("pending@example.com");

        verifyNoInteractions(oneTimeTokenService, emailService);
    }

    @Test
    void forgotPasswordSwallowsEmailFailureSoTheResponseStaysTheSame() {
        User user = localUser(true);
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(oneTimeTokenService.issue(user, RESET_PASSWORD)).thenReturn("reset");
        doThrow(new EmailDeliveryException("down"))
                .when(emailService).sendPasswordReset(anyString(), anyString(), anyString());

        assertDoesNotThrow(() -> service.forgotPassword("ada@example.com"));
    }

    @Test
    void resetPasswordOverwritesThePasswordAndSignsOutEveryDevice() {
        User user = localUser(true);
        when(oneTimeTokenService.consume("reset", RESET_PASSWORD)).thenReturn(user);

        service.resetPassword("reset", "newpassword1");

        assertEquals("hashed:newpassword1", user.getPassword());
        verify(userRepository).save(user);
        verify(refreshTokenService).revokeAll(user);
    }

    // --- fixtures ---

    private static User localUser(boolean enabled) {
        return User.builder().id(7L).firstName("Ada").lastName("Lovelace").email("ada@example.com")
                .password("old-hash").provider(Provider.LOCAL).enabled(enabled).build();
    }

    private static User googleUser() {
        return User.builder().id(8L).firstName("Ada").lastName("Lovelace").email("ada@example.com")
                .provider(Provider.GOOGLE).enabled(true).build();
    }

    private static RefreshToken refreshToken(User user, boolean rememberMe) {
        return RefreshToken.builder().tokenHash("hash").user(user)
                .expiresAt(Instant.now().plusSeconds(3600)).rememberMe(rememberMe).build();
    }

    private static RegisterRequest registerRequest() {
        return new RegisterRequest("Ada", "Lovelace", "ada@example.com", "password123");
    }
}