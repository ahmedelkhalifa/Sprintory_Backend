package team.sprintory_backend.sprint.dto;

import jakarta.validation.constraints.NotNull;

public record MoveSprintTaskRequest(@NotNull Long taskId, Long sprintId, Long afterTaskId) {}
