package team.sprintory_backend.audit.dto;

import java.time.Instant;
import java.util.List;
import team.sprintory_backend.audit.models.AuditAction;
import team.sprintory_backend.audit.models.AuditArea;
import team.sprintory_backend.audit.models.AuditLogEntry;
import team.sprintory_backend.audit.models.AuditTargetType;

/**
 * One audit log entry as the API returns it (docs/audit-log-docs.md §2 and §5). {@code actor} is
 * null when the system acted, {@code project} for non-project actions; {@code changes} is never null.
 */
public record AuditLogEntryResponse(Long id, Instant occurredAt, AuditAction action, AuditArea area,
                                    Actor actor, Target target, ProjectRef project, List<Change> changes) {

    public record Actor(Long id, String name, String email) {
    }

    public record Target(AuditTargetType type, Long id, String name) {
    }

    public record ProjectRef(Long id, String name) {
    }

    public record Change(String field, String oldValue, String newValue) {
    }

    public static AuditLogEntryResponse from(AuditLogEntry entry) {
        return new AuditLogEntryResponse(
                entry.getId(),
                entry.getOccurredAt(),
                entry.getAction(),
                entry.getAction().area(),
                entry.getActorId() == null ? null
                        : new Actor(entry.getActorId(), entry.getActorName(), entry.getActorEmail()),
                new Target(entry.getTargetType(), entry.getTargetId(), entry.getTargetName()),
                entry.getProjectId() == null ? null : new ProjectRef(entry.getProjectId(), entry.getProjectName()),
                entry.getChanges().stream()
                        .map(change -> new Change(change.getField(), change.getOldValue(), change.getNewValue()))
                        .toList());
    }
}