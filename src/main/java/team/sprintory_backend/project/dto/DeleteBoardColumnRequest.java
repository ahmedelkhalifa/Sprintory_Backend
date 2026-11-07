package team.sprintory_backend.project.dto;

import jakarta.validation.constraints.Positive;

/**
 * {@code targetColumnId} is required only when the deleted column contains tasks.
 * {@code confirmOpenSubtasks} confirms moving parents with open subtasks into a DONE column
 * (docs/board-docs.md); absent means false.
 */
public record DeleteBoardColumnRequest(@Positive Long targetColumnId, Boolean confirmOpenSubtasks) {

    public DeleteBoardColumnRequest {
        confirmOpenSubtasks = Boolean.TRUE.equals(confirmOpenSubtasks);
    }

    /** Existing clients may keep their 1-field constructor. */
    public DeleteBoardColumnRequest(Long targetColumnId) {
        this(targetColumnId, false);
    }
}