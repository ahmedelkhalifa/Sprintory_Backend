package team.sprintory_backend.sprint.dto;

import jakarta.validation.constraints.*;

/** Scrum-only project setting; changes affect newly created sprints, not existing dates. */
public record SprintLengthRequest(@NotNull @Min(2) @Max(90) Integer days) {}
