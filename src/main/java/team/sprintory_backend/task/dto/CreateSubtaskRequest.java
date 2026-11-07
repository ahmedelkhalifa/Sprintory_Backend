package team.sprintory_backend.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * One-level subtask; time estimate is minutes, never story points. The description is TipTap
 * JSON like a task's (docs/mentions-docs.md §2), and labels follow the task rules.
 */
public record CreateSubtaskRequest(
        @NotBlank @Size(max = 255) String title,
        @Positive Long assigneeId,
        @Positive Long statusId,
        @Positive Integer estimateMinutes,
        @Size(max = 20) List<@NotBlank @Size(max = 50) String> labels,
        String description
) {
    /** Existing clients may keep their 4-field constructor. */
    public CreateSubtaskRequest(String title, Long assigneeId, Long statusId, Integer estimateMinutes) {
        this(title, assigneeId, statusId, estimateMinutes, null, null);
    }
}