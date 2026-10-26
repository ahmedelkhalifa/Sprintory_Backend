package team.sprintory_backend.task.api;

import org.springframework.data.domain.Sort;
import team.sprintory_backend.exceptionHandler.BadRequestException;

import java.util.Locale;

/**
 * The {@code ?order=} of a project's task list. Each order ends with a unique property, so pages
 * never overlap or skip tasks.
 */
public enum TaskListOrder {
    /** The default: the newest task first. */
    NEWEST(Sort.by(Sort.Direction.DESC, "number")),
    /** As on the board: column by column, then the position inside the column. */
    BOARD(Sort.by("status.position", "boardPosition", "id")),
    /** As in the Scrum product backlog. */
    BACKLOG(Sort.by("backlogRank", "id"));

    private final Sort sort;

    TaskListOrder(Sort sort) {
        this.sort = sort;
    }

    public Sort sort() {
        return sort;
    }

    /** Null or blank means {@link #NEWEST}; matching ignores case. Anything else is a 400. */
    public static TaskListOrder fromQuery(String value) {
        if (value == null || value.isBlank()) {
            return NEWEST;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid 'order': use newest, board or backlog.");
        }
    }
}