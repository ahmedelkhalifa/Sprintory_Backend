package team.sprintory_backend.security.auth.services;

import team.sprintory_backend.security.auth.dto.AuthUserResponse;

/** Internal-only result. Never serialize this record as an HTTP response. */
public record AuthSession(String accessToken, String refreshToken, AuthUserResponse user) {}
