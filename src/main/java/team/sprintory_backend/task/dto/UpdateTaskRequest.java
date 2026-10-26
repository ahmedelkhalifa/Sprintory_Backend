package team.sprintory_backend.task.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import team.sprintory_backend.task.models.TaskPriority;
import team.sprintory_backend.task.models.TaskType;

import java.util.List;

/**
 * Partial update: absent/null fields remain unchanged. Explicit clear flags let callers clear
 * nullable fields without losing the difference between "absent" and "null" in a JSON record.
 * Set labels to [] to remove all labels. Key and sprint are not editable here.
 */
public record UpdateTaskRequest(
        @Size(max = 255) String title,
        TaskType type,
        TaskPriority priority,
        @Positive Long statusId,
        @Positive Long assigneeId,
        @PositiveOrZero Integer estimate,
        List<@NotBlank String> labels,
        String description,
        boolean clearAssignee,
        boolean clearEstimate,
        boolean clearDescription
) {
    @AssertTrue(message = "Title must not be blank")
    public boolean isValidTitle() {
        return title == null || !title.isBlank();
    }

    @AssertTrue(message = "A field cannot be set and cleared together")
    public boolean isConsistent() {
        return !(clearAssignee && assigneeId != null)
                && !(clearEstimate && estimate != null)
                && !(clearDescription && description != null);
    }
}
