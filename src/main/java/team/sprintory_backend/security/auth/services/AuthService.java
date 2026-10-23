package team.sprintory_backend.security.auth.services;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.security.auth.dto.AuthUserResponse;
import team.sprintory_backend.security.auth.dto.LoginRequest;
import team.sprintory_backend.security.auth.dto.RegisterRequest;
import team.sprintory_backend.notification.email.EmailDeliveryException;
import team.sprintory_backend.notification.email.EmailService;
import team.sprintory_backend.security.auth.models.OneTimeTokenPurpose;
import team.sprintory_backend.security.auth.models.RefreshToken;
import team.sprintory_backend.security.exceptions.EmailAlreadyRegisteredException;
import team.sprintory_backend.security.exceptions.InvalidTokenException;
import team.sprintory_backend.security.exceptions.ProviderMismatchException;
import team.sprintory_backend.security.jwt.JwtService;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.user.models.Provider;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;

import java.util.Locale;

/**
 * R01 business logic behind {@code AuthController}. The full contract (flows, errors, cookies)
 * is in docs/auth-endpoints.md.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final AuthenticationManager authenticationManager;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final OneTimeTokenService oneTimeTokenService;
    private final EmailService emailService;

    // Not final: @Value on a final field with @RequiredArgsConstructor fails at startup.
    @Value("${app.frontend-url}")
    private String frontendUrl;
    @Value("${app.auth.verify-email-path}")
    private String verifyEmailPath;
    @Value("${app.auth.reset-password-path}")
    private String resetPasswordPath;

    /**
     * Creates an unverified LOCAL account and emails the verification link. Registering again
     * with an email that is still unverified updates that account and sends a fresh link.
     * The email is the last step, so a delivery failure rolls the whole registration back.
     */
    @Transactional
    public AuthUserResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        User user = userRepository.findByEmail(email)
                .map(AuthService::requireUnverifiedLocalAccount)
                .orElseGet(() -> User.builder()
                        .email(email)
                        .provider(Provider.LOCAL)
                        .enabled(false)
                        .build());
        user.setFirstName(request.firstName().trim());
        user.setLastName(request.lastName().trim());
        user.setPassword(passwordEncoder.encode(request.password()));
        // Flush now: a concurrent registration of the same email fails on the unique
        // constraint here (409), before any email is sent.
        userRepository.saveAndFlush(user);

        String token = oneTimeTokenService.issue(user, OneTimeTokenPurpose.VERIFY_EMAIL);
        emailService.sendEmailVerification(email, user.getFirstName(),
                frontendUrl + verifyEmailPath + "?token=" + token);
        return AuthUserResponse.from(user);
    }

    /** Activates the account behind a verification link. The link works once. */
    @Transactional
    public void verifyEmail(String token) {
        User user = oneTimeTokenService.consume(token, OneTimeTokenPurpose.VERIFY_EMAIL);
        user.setEnabled(true);
        userRepository.save(user);
    }

    /**
     * Email/password login. A Google account is refused before the password check, as the
     * acceptance criteria require. Unknown email and wrong password both surface as
     * BadCredentialsException (401); an unverified account with the right password as
     * DisabledException (403).
     */
    public AuthSession login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        userRepository.findByEmail(email)
                .filter(user -> user.getProvider() == Provider.GOOGLE)
                .ifPresent(user -> {
                    throw new ProviderMismatchException("Account uses Google sign-in.");
                });

        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email, request.password()));
        User user = ((AppUserDetails) authentication.getPrincipal()).getUser();
        return issueSession(user, request.rememberMe());
    }

    /**
     * Rotates the refresh token: the presented one is consumed and a new pair is issued with the
     * same remember-me choice. One transaction, so a failure leaves the old token usable.
     */
    @Transactional
    public AuthSession refresh(String rawRefreshToken) {
        RefreshToken consumed = refreshTokenService.verifyAndConsume(rawRefreshToken);
        User user = consumed.getUser();
        if (!user.isEnabled()) {
            throw new InvalidTokenException("Account is disabled.");
        }
        return issueSession(user, consumed.isRememberMe());
    }

    /** Signs the user out on every device. An unknown or expired token is ignored. */
    public void logout(String rawRefreshToken) {
        refreshTokenService.revokeAllForToken(rawRefreshToken);
    }

    /**
     * Emails a reset link, but only to an existing, verified LOCAL account. Every other case
     * returns quietly, and a delivery failure is logged rather than thrown: either would make
     * registered emails answer differently from unknown ones.
     */
    @Transactional
    public void forgotPassword(String email) {
        userRepository.findByEmail(normalizeEmail(email))
                .filter(user -> user.getProvider() == Provider.LOCAL && user.isEnabled())
                .ifPresent(this::sendPasswordReset);
    }

    /** Sets a new password from a reset link, then signs the user out on every device. */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        User user = oneTimeTokenService.consume(token, OneTimeTokenPurpose.RESET_PASSWORD);
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        refreshTokenService.revokeAll(user);
    }

    private void sendPasswordReset(User user) {
        String token = oneTimeTokenService.issue(user, OneTimeTokenPurpose.RESET_PASSWORD);
        try {
            emailService.sendPasswordReset(user.getEmail(), user.getFirstName(),
                    frontendUrl + resetPasswordPath + "?token=" + token);
        } catch (EmailDeliveryException e) {
            // The user id, not the address, keeps personal data out of the log.
            log.warn("Password reset email for user {} could not be sent", user.getId(), e);
        }
    }

    private static User requireUnverifiedLocalAccount(User existing) {
        if (existing.getProvider() == Provider.GOOGLE) {
            throw new ProviderMismatchException("Account uses Google sign-in.");
        }
        if (existing.isEnabled()) {
            throw new EmailAlreadyRegisteredException("Email is already registered.");
        }
        return existing;
    }

    private AuthSession issueSession(User user, boolean rememberMe) {
        String accessToken = jwtService.generateAccessToken(user.getEmail());
        String refreshToken = refreshTokenService.issue(user, rememberMe);
        return new AuthSession(accessToken, refreshToken, rememberMe, AuthUserResponse.from(user));
    }

    /** Locale.ROOT: a Turkish default locale would lower-case "I" to a dotless "ı". */
    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}