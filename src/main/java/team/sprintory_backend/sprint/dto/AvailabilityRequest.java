package team.sprintory_backend.sprint.dto;

import jakarta.validation.constraints.*;

public record AvailabilityRequest(@NotNull @Min(0) Integer availableDays) {}
