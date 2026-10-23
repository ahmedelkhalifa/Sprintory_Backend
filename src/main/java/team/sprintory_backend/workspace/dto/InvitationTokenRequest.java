package team.sprintory_backend.workspace.dto;

import jakarta.validation.constraints.NotBlank;

/** The token from the emailed invitation link, sent in the body to keep it out of request logs. */
public record InvitationTokenRequest(@NotBlank String token) {}