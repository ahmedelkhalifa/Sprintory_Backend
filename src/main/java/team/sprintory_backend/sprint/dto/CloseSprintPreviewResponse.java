package team.sprintory_backend.sprint.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * What the close and cancel dialogs show before the sprint ends (PMP-41): done and unfinished
 * items and points, with the same split the close itself uses. Done means a DONE-category column;
 * there is no partial credit, so an unfinished story counts 0 points whatever its subtasks say.
 *
 * @param endsEarly      today (UTC) is before {@code endDate}: closing needs {@code confirmEarlyClose}
 * @param daysLeft       calendar days from today to {@code endDate}; 0 on the last day and after it
 * @param plannedSprints the sprints unfinished work can move to on close, in number order
 */
public record CloseSprintPreviewResponse(Long sprintId, String name, LocalDate endDate,
                                         int doneCount, int donePoints,
                                         int unfinishedCount, int unfinishedPoints,
                                         List<UnfinishedItem> unfinished,
                                         boolean endsEarly, int daysLeft,
                                         List<PlannedSprint> plannedSprints) {

    /** An unfinished top-level item, in sprint order; {@code estimate} is null when not estimated. */
    public record UnfinishedItem(Long taskId, String key, String title, Integer estimate,
                                 String statusName, int openSubtasks) {}

    public record PlannedSprint(Long id, String name, LocalDate startDate) {}
}