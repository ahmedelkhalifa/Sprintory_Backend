package team.sprintory_backend.sprint.models;

import team.sprintory_backend.exceptionHandler.BadRequestException;

import java.util.Locale;

/** The {@code ?unit=} of the burndown (docs/burndown-docs.md §2): story points or task count. */
public enum BurndownUnit {
    POINTS,
    TASKS;

    public long total(SprintTotals totals) {
        return this == POINTS ? totals.totalPoints() : totals.totalTasks();
    }

    public long remaining(SprintTotals totals) {
        return this == POINTS ? totals.remainingPoints() : totals.remainingTasks();
    }

    /** Null or blank means {@link #POINTS}; matching ignores case. Anything else is a 400. */
    public static BurndownUnit fromQuery(String value) {
        if (value == null || value.isBlank()) return POINTS;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid 'unit': use points or tasks.");
        }
    }
}
