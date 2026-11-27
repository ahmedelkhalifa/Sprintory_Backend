package team.sprintory_backend.milestone.models;

import java.time.LocalDate;

/**
 * A milestone's status, calculated on every read and never stored (docs/milestones-docs.md §4).
 * It counts tasks, not points.
 */
public enum MilestoneStatus {
    /** No linked tasks: "No tasks yet", never 100%. */
    EMPTY,
    /** Some linked task is open and the target date is today or later. */
    UPCOMING,
    /** Some linked task is open and the target date has passed. */
    OVERDUE,
    /** Every linked task is done. Not permanent: an open task brings it back. */
    REACHED;

    /** {@code today} is the server's date, passed in so tests do not depend on the clock. */
    public static MilestoneStatus of(LocalDate today, LocalDate targetDate, long doneTasks, long totalTasks) {
        if (totalTasks == 0) {
            return EMPTY;
        }
        if (doneTasks == totalTasks) {
            return REACHED;
        }
        return targetDate.isBefore(today) ? OVERDUE : UPCOMING;
    }
}
