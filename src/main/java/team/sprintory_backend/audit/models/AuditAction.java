package team.sprintory_backend.audit.models;

/**
 * Every action the audit log records; docs/audit-log-docs.md §4 says when each one is written
 * and what it contains. A new value needs a migration changing {@code ck_audit_log_entries_action}.
 */
public enum AuditAction {
    WORKSPACE_RENAMED(AuditArea.WORKSPACE),
    MEMBER_INVITED(AuditArea.WORKSPACE),
    MEMBER_JOINED(AuditArea.WORKSPACE),
    MEMBER_REMOVED(AuditArea.WORKSPACE),
    MEMBER_LEFT(AuditArea.WORKSPACE),
    WORKSPACE_ROLE_CHANGED(AuditArea.WORKSPACE),

    TEAM_CREATED(AuditArea.TEAM),
    TEAM_RENAMED(AuditArea.TEAM),
    TEAM_DELETED(AuditArea.TEAM),
    TEAM_MEMBER_ADDED(AuditArea.TEAM),
    TEAM_MEMBER_REMOVED(AuditArea.TEAM),

    PROJECT_CREATED(AuditArea.PROJECT),
    PROJECT_SETTINGS_CHANGED(AuditArea.PROJECT),
    PROJECT_MEMBER_ADDED(AuditArea.PROJECT),
    PROJECT_MEMBER_REMOVED(AuditArea.PROJECT),
    PROJECT_ROLES_CHANGED(AuditArea.PROJECT),

    TASK_DELETED(AuditArea.TASK),
    /** Written by the task restore endpoint (PMP-131). */
    TASK_RESTORED(AuditArea.TASK),

    PASSWORD_RESET(AuditArea.SECURITY),
    LOGIN_FAILED_REPEATED(AuditArea.SECURITY);

    private final AuditArea area;

    AuditAction(AuditArea area) {
        this.area = area;
    }

    public AuditArea area() {
        return area;
    }
}