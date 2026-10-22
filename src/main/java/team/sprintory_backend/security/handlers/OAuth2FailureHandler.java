package team.sprintory_backend.security.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * Google sign-in failed. Send the browser back to the frontend with a machine-readable code
 * so it can show the right message - in particular USE_PASSWORD_LOGIN.
 */
@Component
public class OAuth2FailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2FailureHandler.class);
    private static final String GENERIC_CODE = "OAUTH2_FAILED";

    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();
    private final String failureUrl;

    public OAuth2FailureHandler(@Value("${app.frontend-url}") String frontendUrl,
                                @Value("${app.oauth2.failure-path}") String failurePath) {
        this.failureUrl = frontendUrl + failurePath;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        log.warn("Google sign-in failed", exception);

        String target = UriComponentsBuilder.fromUriString(failureUrl)
                .queryParam("error", errorCode(exception))
                .build()
                .encode()
                .toUriString();
        redirectStrategy.sendRedirect(request, response, target);
    }

    /** Only the code travels to the browser; the message stays in the log. */
    private static String errorCode(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauth2Exception) {
            String code = oauth2Exception.getError().getErrorCode();
            if (StringUtils.hasText(code)) {
                return code;
            }
        }
        return GENERIC_CODE;
    }
}