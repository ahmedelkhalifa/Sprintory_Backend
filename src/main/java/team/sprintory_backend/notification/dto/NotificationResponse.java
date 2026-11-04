package team.sprintory_backend.notification.dto;

import java.time.Instant;
import team.sprintory_backend.notification.models.Notification;
import team.sprintory_backend.notification.models.NotificationCategory;
import team.sprintory_backend.notification.models.NotificationType;

/**
 * One notification as the bell shows it (docs/notifications-docs.md §2). Names are the snapshots
 * stored with it; the nested parts are null when the notification is not about them.
 */
public record NotificationResponse(
        Long id,
        NotificationType type,
        NotificationCategory category,
        Instant createdAt,
        boolean read,
        Instant readAt,
        String message,
        String excerpt,
        Actor actor,
        WorkspaceRef workspace,
        ProjectRef project,
        TaskRef task
) {

    public record Actor(Long id, String name) {}

    public record WorkspaceRef(Long id, String name) {}

    public record ProjectRef(Long id, String key, String name) {}

    public record TaskRef(Long id, String key, String title) {}

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.getType(),
                n.getType().category(),
                n.getCreatedAt(),
                n.isRead(),
                n.getReadAt(),
                n.getMessage(),
                n.getExcerpt(),
                n.getActorId() == null ? null : new Actor(n.getActorId(), n.getActorName()),
                // getId() on the lazy proxy reads the foreign key without loading the workspace.
                n.getWorkspace() == null ? null : new WorkspaceRef(n.getWorkspace().getId(), n.getWorkspaceName()),
                n.getProjectId() == null ? null : new ProjectRef(n.getProjectId(), n.getProjectKey(), n.getProjectName()),
                n.getTaskId() == null ? null : new TaskRef(n.getTaskId(), n.getTaskKey(), n.getTaskTitle()));
    }
}