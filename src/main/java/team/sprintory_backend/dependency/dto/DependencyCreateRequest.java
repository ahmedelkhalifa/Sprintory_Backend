package team.sprintory_backend.dependency.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record DependencyCreateRequest(@NotNull @Positive Long blockerId, @NotNull @Positive Long blockedId) {
}
