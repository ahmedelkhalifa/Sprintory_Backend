package team.sprintory_backend.task.services;

import team.sprintory_backend.audit.models.AuditTarget;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.user.models.User;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * The fields of a task as the history shows them (docs/task-history-docs.md §3.2): names, not
 * ids. Taken before and after an edit; {@link TaskStatusHistoryService#recordChanges} writes one
 * entry per field that differs. Status is not here: moves go through {@code record}.
 * {@code description} holds the raw text only to detect a change; it is never stored.
 */
record TaskSnapshot(String title, String type, String priority, String assignee, String estimate,
                    String estimateMinutes, String labels, String description) {

    static TaskSnapshot of(Task task) {
        return new TaskSnapshot(task.getTitle(),
                task.getType() == null ? null : task.getType().name(),
                task.getPriority() == null ? null : task.getPriority().name(),
                person(task.getAssignee()),
                number(task.getEstimate()),
                number(task.getEstimateMinutes()),
                labels(task.getLabels()),
                task.getDescription());
    }

    private static String person(User user) {
        return (user == null) ? null : AuditTarget.displayName(user);
    }

    private static String number(Integer value) {
        return (value == null) ? null : value.toString();
    }

    /** Sorted as the API returns them, joined with ", "; no labels is null. */
    private static String labels(Collection<String> labels) {
        return (labels == null || labels.isEmpty()) ? null
                : labels.stream().sorted().collect(Collectors.joining(", "));
    }
}