package team.sprintory_backend.audit.models;

import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.team.models.Team;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.models.Workspace;

/**
 * What an audit entry's action was done to: type, id and the name at that moment. The factories
 * build the names in one place, so every entry shows people and tasks the same way.
 */
public record AuditTarget(AuditTargetType type, Long id, String name) {

    public static AuditTarget workspace(Workspace workspace) {
        return new AuditTarget(AuditTargetType.WORKSPACE, workspace.getId(), workspace.getName());
    }

    public static AuditTarget team(Team team) {
        return new AuditTarget(AuditTargetType.TEAM, team.getId(), team.getName());
    }

    public static AuditTarget project(Project project) {
        return new AuditTarget(AuditTargetType.PROJECT, project.getId(), project.getName());
    }

    /** Named "KEY title", e.g. "MOB-42 Fix login". */
    public static AuditTarget task(Task task) {
        return new AuditTarget(AuditTargetType.TASK, task.getId(), task.getKey() + " " + task.getTitle());
    }

    public static AuditTarget user(User user) {
        return new AuditTarget(AuditTargetType.USER, user.getId(), displayName(user));
    }

    /** An invitation is addressed to an email that may have no account yet, so it has no id. */
    public static AuditTarget invitation(String email) {
        return new AuditTarget(AuditTargetType.INVITATION, null, email);
    }

    /** "First Last", the way every audit entry names a person. */
    public static String displayName(User user) {
        return (user.getFirstName() + " " + user.getLastName()).trim();
    }
}