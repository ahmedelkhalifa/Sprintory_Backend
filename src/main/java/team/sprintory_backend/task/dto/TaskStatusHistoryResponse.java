package team.sprintory_backend.task.dto;

import java.time.Instant;
import team.sprintory_backend.task.models.TaskStatusHistory;

public record TaskStatusHistoryResponse(Long id, Long fromColumnId, String fromColumnName,
                                        Long toColumnId, String toColumnName,
                                        Long actorId, Instant changedAt) {
    public static TaskStatusHistoryResponse from(TaskStatusHistory event) {
        return new TaskStatusHistoryResponse(event.getId(), event.getFromColumnId(),
                event.getFromColumnName(), event.getToColumnId(), event.getToColumnName(),
                event.getActorId(), event.getChangedAt());
    }
}
