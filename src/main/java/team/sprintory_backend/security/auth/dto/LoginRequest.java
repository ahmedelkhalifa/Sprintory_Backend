package team.sprintory_backend.security.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** rememberMe is accepted for compatibility with the current frontend; its semantics are pending. */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password,
        boolean rememberMe
) {}
