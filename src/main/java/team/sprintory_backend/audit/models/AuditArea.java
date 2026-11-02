package team.sprintory_backend.audit.models;

/** The group an {@link AuditAction} belongs to; the audit log's {@code ?areas=} filter uses it. */
public enum AuditArea {
    WORKSPACE,
    TEAM,
    PROJECT,
    TASK,
    SECURITY
}