package team.sprintory_backend.notification.models;

/**
 * A row of the preferences matrix (docs/notifications-docs.md §6), in display order. Each
 * category carries its defaults, which apply to anyone who never changed it, and whether it is
 * locked (always on in both channels). A new value needs a migration for
 * {@code ck_notification_preferences_category}.
 */
public enum NotificationCategory {
    ASSIGNMENT(true, true, false),
    MENTION(true, true, false),
    COMMENT(true, false, false),
    STATUS_CHANGE(true, false, false),
    PROJECT_MEMBERSHIP(true, false, false),
    SPRINT(true, false, false),
    INVITATION(true, true, true),
    SECURITY(true, true, true);

    private final boolean defaultInApp;
    private final boolean defaultEmail;
    private final boolean locked;

    NotificationCategory(boolean defaultInApp, boolean defaultEmail, boolean locked) {
        this.defaultInApp = defaultInApp;
        this.defaultEmail = defaultEmail;
        this.locked = locked;
    }

    public boolean defaultInApp() {
        return defaultInApp;
    }

    public boolean defaultEmail() {
        return defaultEmail;
    }

    public boolean isDefaultEnabled(NotificationChannel channel) {
        return channel == NotificationChannel.IN_APP ? defaultInApp : defaultEmail;
    }

    /** Locked categories are always on in both channels and cannot be changed. */
    public boolean locked() {
        return locked;
    }
}