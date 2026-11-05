package team.sprintory_backend.task.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * A drag on the board: the column to put the task in, and the task to place it after in that
 * column. {@code afterTaskId} null puts it first.
 */
public record MoveTaskRequest(
        @NotNull @Positive Long statusId,
        @Positive Long afterTaskId,
        Boolean confirmOpenSubtasks
) {
    public MoveTaskRequest(Long statusId, Long afterTaskId) {
        this(statusId, afterTaskId, false);
    }

    public MoveTaskRequest {
        confirmOpenSubtasks = Boolean.TRUE.equals(confirmOpenSubtasks);
    }
}