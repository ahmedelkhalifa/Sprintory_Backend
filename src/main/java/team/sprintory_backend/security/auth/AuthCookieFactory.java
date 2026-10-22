package team.sprintory_backend.security.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Builds the HttpOnly auth cookies (and their cleared versions for logout).
 * The refresh cookie is scoped to {@code /api/v1/auth} so it is only sent to auth endpoints.
 */
@Component
public class AuthCookieFactory {

    public static final String ACCESS_COOKIE = "access_token";
    public static final String REFRESH_COOKIE = "refresh_token";

    private static final String ROOT_PATH = "/";
    private static final String REFRESH_PATH = "/api/v1/auth";
    private static final String SAME_SITE = "Lax";
    /** A negative Max-Age leaves the attribute out, making a session cookie. */
    private static final long SESSION_COOKIE = -1;

    private final boolean secure;
    private final long accessMaxAge;
    private final long refreshMaxAge;

    public AuthCookieFactory(
            @Value("${app.cookie.secure}") boolean secure,
            @Value("${app.jwt.access-expiration-ms}") long accessTtlMs,
            @Value("${app.jwt.refresh-expiration-ms}") long refreshTtlMs) {
        this.secure = secure;
        this.accessMaxAge = accessTtlMs / 1000;
        this.refreshMaxAge = refreshTtlMs / 1000;
    }

    /**
     * @param persistent {@code true} for "remember me" (cookie survives a browser restart),
     *                   {@code false} for a session cookie the browser drops when it closes
     */
    public ResponseCookie access(String token, boolean persistent) {
        return build(ACCESS_COOKIE, token, ROOT_PATH, persistent ? accessMaxAge : SESSION_COOKIE);
    }

    /** @param persistent same meaning as in {@link #access(String, boolean)} */
    public ResponseCookie refresh(String token, boolean persistent) {
        return build(REFRESH_COOKIE, token, REFRESH_PATH, persistent ? refreshMaxAge : SESSION_COOKIE);
    }

    public ResponseCookie clearAccess() {
        return build(ACCESS_COOKIE, "", ROOT_PATH, 0);
    }

    public ResponseCookie clearRefresh() {
        return build(REFRESH_COOKIE, "", REFRESH_PATH, 0);
    }

    private ResponseCookie build(String name, String value, String path, long maxAgeSeconds) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(SAME_SITE)
                .path(path)
                .maxAge(maxAgeSeconds)
                .build();
    }
}
