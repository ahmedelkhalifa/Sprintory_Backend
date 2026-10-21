package team.sprintory_backend.security.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import team.sprintory_backend.security.auth.AuthCookieFactory;
import team.sprintory_backend.security.auth.services.RefreshTokenService;
import team.sprintory_backend.security.jwt.JwtService;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;

import java.io.IOException;

/**
 * Google sign-in succeeded. Issue our own cookies and hand the browser back to the frontend,
 * which lives in a separate application.
 */
@Component
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AuthCookieFactory cookieFactory;
    private final String successUrl;

    public OAuth2SuccessHandler(UserRepository userRepository,
                                JwtService jwtService,
                                RefreshTokenService refreshTokenService,
                                AuthCookieFactory cookieFactory,
                                @Value("${app.frontend-url}") String frontendUrl,
                                @Value("${app.oauth2.success-path}") String successPath) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.cookieFactory = cookieFactory;
        this.successUrl = frontendUrl + successPath;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OidcUser principal = (OidcUser) authentication.getPrincipal();
        String email = principal.getEmail().trim().toLowerCase();

        // AppOidcUserService has already created or verified this row.
        User user = userRepository.findByEmail(email).orElseThrow(
                () -> new IllegalStateException("Google sign-in produced no local user for " + email));

        String accessToken = jwtService.generateAccessToken(email);
        String refreshToken = refreshTokenService.issue(user);

        response.addHeader(HttpHeaders.SET_COOKIE, cookieFactory.access(accessToken).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, cookieFactory.refresh(refreshToken).toString());

        // Tokens travel in the cookies above, never in the redirect URL: query strings end up in
        // browser history, referrer headers and access logs.
        redirectStrategy.sendRedirect(request, response, successUrl);
    }
}