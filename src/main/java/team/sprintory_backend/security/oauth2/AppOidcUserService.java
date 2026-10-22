package team.sprintory_backend.security.oauth2;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import team.sprintory_backend.user.models.Provider;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;

import java.util.List;
import java.util.Locale;

/**
 * Decides what a Google sign-in means for our own user table: provision a new account,
 * sign an existing Google account in, or refuse because the address belongs to a password account.
 *
 * <p>This extends the OIDC service rather than the plain OAuth2 one because the registration
 * requests the {@code openid} scope. Registering the plain one instead would never be called.
 */
@Service
@RequiredArgsConstructor
public class AppOidcUserService extends OidcUserService {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser googleUser = super.loadUser(userRequest);

        String email = googleUser.getEmail();
        if (!StringUtils.hasText(email)) {
            throw failure("EMAIL_MISSING", "Google did not return an email address for this account.");
        }
        if (!Boolean.TRUE.equals(googleUser.getEmailVerified())) {
            throw failure("EMAIL_NOT_VERIFIED", "This Google account has no verified email address.");
        }

        String normalisedEmail = email.trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmail(normalisedEmail)
                .map(AppOidcUserService::requireGoogleAccount)
                .orElseGet(() -> provision(normalisedEmail, googleUser));

        return new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())),
                googleUser.getIdToken(),
                googleUser.getUserInfo(),
                StandardClaimNames.EMAIL);
    }

    private static User requireGoogleAccount(User existing) {
        if (existing.getProvider() == Provider.GOOGLE) {
            return existing;
        }
        throw failure("USE_PASSWORD_LOGIN",
                "This email is already registered with a password. Sign in with your password instead.");
    }

    private User provision(String email, OidcUser googleUser) {
        return userRepository.save(User.builder()
                .email(email)
                .firstName(firstName(googleUser, email))
                .lastName(lastName(googleUser))
                .provider(Provider.GOOGLE)
                .role(Role.WORKSPACE_MEMBER)
                // Google has already verified the address, so there is nothing to activate.
                .enabled(true)
                .build());
    }

    private static String firstName(OidcUser googleUser, String email) {
        if (StringUtils.hasText(googleUser.getGivenName())) {
            return googleUser.getGivenName();
        }
        if (StringUtils.hasText(googleUser.getFullName())) {
            return googleUser.getFullName().trim().split("\\s+")[0];
        }
        int at = email.indexOf('@');
        return (at > 0) ? email.substring(0, at) : email;
    }

    /** Google omits family_name for single-name accounts; the column is not null, so use "". */
    private static String lastName(OidcUser googleUser) {
        return StringUtils.hasText(googleUser.getFamilyName()) ? googleUser.getFamilyName() : "";
    }

    private static OAuth2AuthenticationException failure(String code, String message) {
        return new OAuth2AuthenticationException(new OAuth2Error(code, message, null), message);
    }
}