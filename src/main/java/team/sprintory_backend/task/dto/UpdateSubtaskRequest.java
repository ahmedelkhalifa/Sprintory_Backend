package team.sprintory_backend.task.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Partial subtask edit; explicit clear flags distinguish unset fields from null. */
public record UpdateSubtaskRequest(
        @Size(max = 255) String title,
        @Positive Long assigneeId,
        @Positive Long statusId,
        @Positive Integer estimateMinutes,
        Boolean clearAssignee,
        Boolean clearEstimateMinutes
) {
    public UpdateSubtaskRequest {
        clearAssignee = Boolean.TRUE.equals(clearAssignee);
        clearEstimateMinutes = Boolean.TRUE.equals(clearEstimateMinutes);
    }

    @AssertTrue(message = "Title must not be blank")
    public boolean isValidTitle() {
        return title == null || !title.isBlank();
    }

    @AssertTrue(message = "A field cannot be set and cleared together")
    public boolean isConsistent() {
        return !(clearAssignee && assigneeId != null)
                && !(clearEstimateMinutes && estimateMinutes != null);
    }
}
