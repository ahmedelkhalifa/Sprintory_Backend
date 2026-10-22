package team.sprintory_backend.security.auth.services;

import team.sprintory_backend.security.auth.dto.AuthUserResponse;

/**
 * Internal-only result. Never serialize this record as an HTTP response.
 *
 * @param rememberMe whether the controller sets persistent cookies or browser-session cookies
 */
public record AuthSession(String accessToken, String refreshToken, boolean rememberMe, AuthUserResponse user) {}