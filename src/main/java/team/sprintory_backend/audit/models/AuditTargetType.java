package team.sprintory_backend.audit.models;

/** What an audit entry's action was done to. {@code INVITATION} has no id: its name is the email. */
public enum AuditTargetType {
    WORKSPACE,
    TEAM,
    PROJECT,
    TASK,
    USER,
    INVITATION
}