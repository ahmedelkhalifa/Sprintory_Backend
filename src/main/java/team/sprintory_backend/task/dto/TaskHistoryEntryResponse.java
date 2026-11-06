package team.sprintory_backend.task.dto;

import team.sprintory_backend.task.models.TaskHistoryEntry;
import team.sprintory_backend.task.models.TaskHistoryField;
import team.sprintory_backend.task.models.TaskHistoryType;

import java.time.Instant;

/** One entry of {@code GET …/tasks/{taskId}/history} (docs/task-history-docs.md §2). */
public record TaskHistoryEntryResponse(Long id, TaskHistoryType type, TaskHistoryField field,
                                       String oldValue, String newValue, Long refId,
                                       Actor actor, Instant occurredAt) {

    /** Who did it; the name is the one they had at the time. */
    public record Actor(Long id, String name) {
    }

    public static TaskHistoryEntryResponse from(TaskHistoryEntry entry) {
        return new TaskHistoryEntryResponse(entry.getId(), entry.getType(), entry.getField(),
                entry.getOldValue(), entry.getNewValue(), entry.getRefId(),
                new Actor(entry.getActorId(), entry.getActorName()), entry.getOccurredAt());
    }
}