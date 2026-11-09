package team.sprintory_backend.sprint.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cancelling an active sprint whose goal became obsolete (PMP-41); the reason is required. */
public record CancelSprintRequest(@NotBlank @Size(max = 1000) String reason) {}