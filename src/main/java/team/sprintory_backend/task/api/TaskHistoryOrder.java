package team.sprintory_backend.task.api;

import org.springframework.data.domain.Sort;
import team.sprintory_backend.exceptionHandler.BadRequestException;

import java.util.Locale;

/**
 * The {@code ?order=} of a task's activity feed (docs/task-history-docs.md §2). Both orders end
 * with the id, so entries written in the same millisecond keep their place and pages never overlap.
 */
public enum TaskHistoryOrder {
    /** The default: the latest change first. */
    NEWEST(Sort.by(Sort.Direction.DESC, "occurredAt", "id")),
    /** As it happened: the creation first. */
    OLDEST(Sort.by(Sort.Direction.ASC, "occurredAt", "id"));

    private final Sort sort;

    TaskHistoryOrder(Sort sort) {
        this.sort = sort;
    }

    public Sort sort() {
        return sort;
    }

    /** Null or blank means {@link #NEWEST}; matching ignores case. Anything else is a 400. */
    public static TaskHistoryOrder fromQuery(String value) {
        if (value == null || value.isBlank()) {
            return NEWEST;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid 'order': use newest or oldest.");
        }
    }
}