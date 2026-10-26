package team.sprintory_backend.task.dto;

import team.sprintory_backend.task.models.TaskPriority;
import team.sprintory_backend.task.models.TaskType;

import java.time.Instant;
import java.util.List;

/** Public task representation, never exposing the soft-delete marker or database entities. */
public record TaskResponse(
        Long id,
        Long projectId,
        String key,
        String title,
        TaskType type,
        TaskPriority priority,
        Long statusId,
        String statusName,
        Long assigneeId,
        Integer estimate,
        List<String> labels,
        String description,
        Long sprintId,
        Instant createdAt,
        Instant updatedAt
) {
}
