package team.sprintory_backend.notification.dto;

/**
 * Where to navigate after opening a notification (docs/notifications-docs.md §3.3). Only the ids
 * the kind needs are set; NONE has nothing to open (invitations, security).
 */
public record NotificationTargetResponse(Kind kind, Long workspaceId, Long projectId, Long taskId, String taskKey) {

    public enum Kind { TASK, PROJECT, NONE }

    public static NotificationTargetResponse task(Long workspaceId, Long projectId, Long taskId, String taskKey) {
        return new NotificationTargetResponse(Kind.TASK, workspaceId, projectId, taskId, taskKey);
    }

    public static NotificationTargetResponse project(Long workspaceId, Long projectId) {
        return new NotificationTargetResponse(Kind.PROJECT, workspaceId, projectId, null, null);
    }

    public static NotificationTargetResponse none() {
        return new NotificationTargetResponse(Kind.NONE, null, null, null, null);
    }
}