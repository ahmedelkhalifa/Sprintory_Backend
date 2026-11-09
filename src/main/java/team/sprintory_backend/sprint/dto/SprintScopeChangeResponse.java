package team.sprintory_backend.sprint.dto;

import java.time.Instant;
import team.sprintory_backend.sprint.models.SprintScopeChange;

public record SprintScopeChangeResponse(Long id, Long sprintId, Long taskId, String taskKey,
       SprintScopeChange.Action action, Long actorId, Instant changedAt) {
    public static SprintScopeChangeResponse from(SprintScopeChange event) {
        return new SprintScopeChangeResponse(event.getId(), event.getSprint().getId(), event.getTaskId(),
                event.getTaskKey(), event.getAction(), event.getActorId(), event.getChangedAt());
    }
}
