package team.sprintory_backend.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** One-level subtask; time estimate is minutes, never story points. */
public record CreateSubtaskRequest(
        @NotBlank @Size(max = 255) String title,
        @Positive Long assigneeId,
        @Positive Long statusId,
        @Positive Integer estimateMinutes
) {}
