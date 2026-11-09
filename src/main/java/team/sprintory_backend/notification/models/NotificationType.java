package team.sprintory_backend.notification.models;

/**
 * What a notification is about (docs/notifications-docs.md §5). Each type belongs to one
 * preference category. A new value needs a migration for {@code ck_notifications_type}.
 */
public enum NotificationType {
    TASK_ASSIGNED(NotificationCategory.ASSIGNMENT),
    TASK_STATUS_CHANGED(NotificationCategory.STATUS_CHANGE),
    TASK_COMMENTED(NotificationCategory.COMMENT),
    MENTIONED(NotificationCategory.MENTION),
    PROJECT_MEMBER_ADDED(NotificationCategory.PROJECT_MEMBERSHIP),
    PROJECT_ROLES_CHANGED(NotificationCategory.PROJECT_MEMBERSHIP),
    SPRINT_STARTED(NotificationCategory.SPRINT),
    SPRINT_COMPLETED(NotificationCategory.SPRINT),
    SPRINT_CANCELLED(NotificationCategory.SPRINT),
    /** Daily, to the Scrum Masters, while the active sprint is past its end date (PMP-41). */
    SPRINT_OVERDUE(NotificationCategory.SPRINT),
    WORKSPACE_INVITATION(NotificationCategory.INVITATION),
    PASSWORD_RESET(NotificationCategory.SECURITY),
    LOGIN_FAILED_REPEATED(NotificationCategory.SECURITY);

    private final NotificationCategory category;

    NotificationType(NotificationCategory category) {
        this.category = category;
    }

    public NotificationCategory category() {
        return category;
    }
}