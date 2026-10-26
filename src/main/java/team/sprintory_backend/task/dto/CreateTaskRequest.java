package team.sprintory_backend.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import team.sprintory_backend.task.models.TaskPriority;
import team.sprintory_backend.task.models.TaskType;

import java.util.List;

/**
 * Task creation. The backend generates key, default board status, and sprint assignment;
 * the client must not choose them. Null type/priority means TASK/MEDIUM.
 */
public record CreateTaskRequest(
        @NotBlank @Size(max = 255) String title,
        TaskType type,
        TaskPriority priority,
        @Positive Long assigneeId,
        @PositiveOrZero Integer estimate,
        List<@NotBlank String> labels,
        String description
) {
}
