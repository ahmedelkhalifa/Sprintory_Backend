package team.sprintory_backend.notification.dto;

/**
 * Where to navigate after opening a notification (docs/notifications-docs.md §3.3). Only the ids
 * the kind needs are set; NONE has nothing to open (invitations, security). SPRINT opens the
 * sprint's page, which links to its retrospective (PMP-44).
 */
public record NotificationTargetResponse(Kind kind, Long workspaceId, Long projectId, Long taskId, String taskKey,
                                         Long sprintId) {

    public enum Kind { TASK, SPRINT, PROJECT, NONE }

    public static NotificationTargetResponse task(Long workspaceId, Long projectId, Long taskId, String taskKey) {
        return new NotificationTargetResponse(Kind.TASK, workspaceId, projectId, taskId, taskKey, null);
    }

    public static NotificationTargetResponse sprint(Long workspaceId, Long projectId, Long sprintId) {
        return new NotificationTargetResponse(Kind.SPRINT, workspaceId, projectId, null, null, sprintId);
    }

    public static NotificationTargetResponse project(Long workspaceId, Long projectId) {
        return new NotificationTargetResponse(Kind.PROJECT, workspaceId, projectId, null, null, null);
    }

    public static NotificationTargetResponse none() {
        return new NotificationTargetResponse(Kind.NONE, null, null, null, null, null);
    }
}
