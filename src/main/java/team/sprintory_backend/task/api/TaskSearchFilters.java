package team.sprintory_backend.task.api;

import team.sprintory_backend.exceptionHandler.BadRequestException;

import java.util.Locale;
import java.util.Set;

/**
 * PMP-105: validated, immutable filter values for a project's task list.
 *
 * <p>Designed for use with Spring MVC query parameters on
 * {@code GET /api/v1/projects/{projectId}/tasks}. This deliberately does not
 * depend on PMP-104's Task entity, TaskService, or enum implementations.
 * The application service must still enforce project membership, exclude
 * soft-deleted tasks, and combine the filters in a database query.
 */
public record TaskSearchFilters(
        String q,
        String type,
        String priority,
        Long statusId,
        Long assigneeId,
        String label
) {
    private static final Set<String> TYPES = Set.of("STORY", "TASK", "BUG");
    private static final Set<String> PRIORITIES = Set.of("HIGHEST", "HIGH", "MEDIUM", "LOW", "LOWEST");

    public TaskSearchFilters {
        q = optionalText(q);
        type = optionalChoice(type, TYPES, "type");
        priority = optionalChoice(priority, PRIORITIES, "priority");
        statusId = optionalPositiveId(statusId, "statusId");
        assigneeId = optionalPositiveId(assigneeId, "assigneeId");
        label = optionalText(label);
    }

    /**
     * A controller can pass its six optional {@code @RequestParam} values
     * directly to this factory. Null/blank values mean "no filter".
     */
    public static TaskSearchFilters fromQuery(String q, String type, String priority,
                                              Long statusId, Long assigneeId, String label) {
        return new TaskSearchFilters(q, type, priority, statusId, assigneeId, label);
    }

    public boolean isUnfiltered() {
        return q == null && type == null && priority == null
                && statusId == null && assigneeId == null && label == null;
    }

    private static String optionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String optionalChoice(String value, Set<String> allowed, String field) {
        String cleaned = optionalText(value);
        if (cleaned == null) {
            return null;
        }
        String normalized = cleaned.toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BadRequestException("Invalid '" + field + "' filter.");
        }
        return normalized;
    }

    private static Long optionalPositiveId(Long value, String field) {
        if (value != null && value <= 0) {
            throw new BadRequestException("'" + field + "' must be a positive ID.");
        }
        return value;
    }
}
