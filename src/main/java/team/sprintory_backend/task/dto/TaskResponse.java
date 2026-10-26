package team.sprintory_backend.task.dto;

import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.models.TaskPriority;
import team.sprintory_backend.task.models.TaskType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    /** Labels sorted for a stable order. {@code sprintId} stays null until sprints exist (R14). */
    public static TaskResponse from(Task task) {
        return new TaskResponse(task.getId(), task.getProject().getId(), task.getKey(), task.getTitle(),
                task.getType(), task.getPriority(), task.getStatus().getId(), task.getStatus().getName(),
                task.getAssignee() == null ? null : task.getAssignee().getId(), task.getEstimate(),
                task.getLabels().stream().sorted().toList(), task.getDescription(), null,
                toInstant(task.getCreatedAt()), toInstant(task.getUpdatedAt()));
    }

    /** The audit columns are local date-times written in the server's zone. */
    private static Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }
}