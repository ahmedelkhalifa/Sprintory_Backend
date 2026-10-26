package team.sprintory_backend.task.dto;

import jakarta.validation.constraints.Positive;

/** A drag in the Scrum backlog: the task to place it after; null puts it first. */
public record ReorderBacklogRequest(
        @Positive Long afterTaskId
) {
}