package team.sprintory_backend.project.charter.dto;

import team.sprintory_backend.project.charter.models.CharterChangeType;
import team.sprintory_backend.project.charter.models.CharterHistoryEntry;
import java.time.Instant;

public record CharterHistoryResponse(Long id, Long actorId, String actorName, Instant occurredAt,
        CharterChangeType changeType, String oldValue, String newValue) {
    public static CharterHistoryResponse from(CharterHistoryEntry h) {
        return new CharterHistoryResponse(h.getId(), h.getActorId(), h.getActorName(),
                h.getOccurredAt(), h.getChangeType(), h.getOldValue(), h.getNewValue());
    }
}
