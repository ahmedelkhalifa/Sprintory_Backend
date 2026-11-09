package team.sprintory_backend.sprint.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import team.sprintory_backend.sprint.models.SprintGoalAchieved;

/**
 * Closing an active sprint (PMP-41). Unfinished work goes to the top of the backlog by default,
 * to {@code carryOverSprintId} (a PLANNED sprint), or to a new sprint when {@code createSprint};
 * never both. Before the end date the close needs {@code confirmEarlyClose = true}.
 */
public record CompleteSprintRequest(
        Long carryOverSprintId,
        Boolean createSprint,
        @NotNull SprintGoalAchieved goalAchieved,
        @Size(max = 1000) String goalNote,
        Boolean confirmEarlyClose
) {
    public CompleteSprintRequest {
        createSprint = Boolean.TRUE.equals(createSprint);
        confirmEarlyClose = Boolean.TRUE.equals(confirmEarlyClose);
    }
}