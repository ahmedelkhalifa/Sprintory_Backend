package team.sprintory_backend.sprint.models;

/**
 * A sprint's live top-level tasks right now: what a daily snapshot stores and what the burndown
 * shows for today (PMP-42). Remaining = not in a DONE-category column; unestimated tasks count 0 points.
 */
public record SprintTotals(long totalPoints, long remainingPoints, long totalTasks, long remainingTasks,
                           long unestimatedTasks) {
}
