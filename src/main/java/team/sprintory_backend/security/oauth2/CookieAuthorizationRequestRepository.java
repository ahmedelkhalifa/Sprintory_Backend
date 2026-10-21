package team.sprintory_backend.security.oauth2;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the in-flight OAuth2 authorization request in a short-lived cookie instead of the
 * HTTP session. The only implementation Spring ships is session-backed, and this application
 * runs STATELESS, so without this the Google callback fails with authorization_request_not_found.
 */
@Component
public class CookieAuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final String COOKIE_NAME = "oauth2_auth_request";
    private static final String ROOT_PATH = "/";

    /** Long enough for a person to sign in at Google, short enough not to linger. */
    private static final long TTL_SECONDS = 180;

    /**
     * Must be Lax, not Strict: the callback from Google is a cross-site navigation, and Strict
     * would withhold the cookie exactly when we need to read it back.
     */
    private static final String SAME_SITE = "Lax";

    private final ObjectMapper objectMapper;
    private final boolean secure;

    public CookieAuthorizationRequestRepository(ObjectMapper objectMapper,
                                                @Value("${app.cookie.secure}") boolean secure) {
        this.objectMapper = objectMapper;
        this.secure = secure;
    }

    @Override
    public @Nullable OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        String value = readCookie(request);
        return (value != null) ? deserialize(value) : null;
    }

    @Override
    public void saveAuthorizationRequest(@Nullable OAuth2AuthorizationRequest authorizationRequest,
                                         HttpServletRequest request, HttpServletResponse response) {
        if (authorizationRequest == null) {
            write(response, "", 0);
            return;
        }
        write(response, serialize(authorizationRequest), TTL_SECONDS);
    }

    @Override
    public @Nullable OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                           HttpServletResponse response) {
        OAuth2AuthorizationRequest loaded = loadAuthorizationRequest(request);
        write(response, "", 0);
        return loaded;
    }

    private String serialize(OAuth2AuthorizationRequest source) {
        SavedAuthorizationRequest saved = new SavedAuthorizationRequest(
                source.getAuthorizationUri(),
                source.getClientId(),
                source.getRedirectUri(),
                source.getScopes(),
                source.getState(),
                source.getAdditionalParameters(),
                source.getAttributes());
        byte[] json = objectMapper.writeValueAsBytes(saved);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    }

    private @Nullable OAuth2AuthorizationRequest deserialize(String cookieValue) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(cookieValue);
            SavedAuthorizationRequest saved =
                    objectMapper.readValue(json, SavedAuthorizationRequest.class);
            return OAuth2AuthorizationRequest.authorizationCode()
                    .authorizationUri(saved.authorizationUri())
                    .clientId(saved.clientId())
                    .redirectUri(saved.redirectUri())
                    .scopes(saved.scopes())
                    .state(saved.state())
                    .additionalParameters(saved.additionalParameters())
                    .attributes(saved.attributes())
                    .build();
        } catch (RuntimeException e) {
            // A tampered or truncated cookie is treated as no cookie at all; Spring then reports
            // the failure through the normal OAuth2 error path rather than a 500.
            return null;
        }
    }

    private @Nullable String readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void write(HttpServletResponse response, String value, long maxAgeSeconds) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(SAME_SITE)
                .path(ROOT_PATH)
                .maxAge(maxAgeSeconds)
                .build()
                .toString());
    }

    /** The parts of the authorization request worth carrying across the redirect. */
    public record SavedAuthorizationRequest(
            String authorizationUri,
            String clientId,
            @Nullable String redirectUri,
            Set<String> scopes,
            @Nullable String state,
            Map<String, Object> additionalParameters,
            Map<String, Object> attributes) {
    }
}