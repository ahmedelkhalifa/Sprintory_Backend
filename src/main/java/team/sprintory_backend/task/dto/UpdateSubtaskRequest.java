package team.sprintory_backend.task.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Partial subtask edit; explicit clear flags distinguish unset fields from null.
 * {@code labels: []} removes all labels.
 */
public record UpdateSubtaskRequest(
        @Size(max = 255) String title,
        @Positive Long assigneeId,
        @Positive Long statusId,
        @Positive Integer estimateMinutes,
        Boolean clearAssignee,
        Boolean clearEstimateMinutes,
        @Size(max = 20) List<@NotBlank @Size(max = 50) String> labels,
        String description,
        Boolean clearDescription
) {
    public UpdateSubtaskRequest {
        clearAssignee = Boolean.TRUE.equals(clearAssignee);
        clearEstimateMinutes = Boolean.TRUE.equals(clearEstimateMinutes);
        clearDescription = Boolean.TRUE.equals(clearDescription);
    }

    /** Existing clients may keep their 6-field constructor. */
    public UpdateSubtaskRequest(String title, Long assigneeId, Long statusId, Integer estimateMinutes,
                                Boolean clearAssignee, Boolean clearEstimateMinutes) {
        this(title, assigneeId, statusId, estimateMinutes, clearAssignee, clearEstimateMinutes,
                null, null, null);
    }

    @AssertTrue(message = "Title must not be blank")
    public boolean isValidTitle() {
        return title == null || !title.isBlank();
    }

    @AssertTrue(message = "A field cannot be set and cleared together")
    public boolean isConsistent() {
        return !(clearAssignee && assigneeId != null)
                && !(clearEstimateMinutes && estimateMinutes != null)
                && !(clearDescription && description != null);
    }
}