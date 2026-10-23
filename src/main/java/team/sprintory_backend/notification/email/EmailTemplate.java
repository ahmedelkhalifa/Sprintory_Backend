package team.sprintory_backend.notification.email;

/** Supported transactional email variants. The layout is shared across all of them. */
public enum EmailTemplate {
    EMAIL_VERIFICATION,
    PASSWORD_RESET,
    WORKSPACE_INVITATION,
    NOTIFICATION
}
