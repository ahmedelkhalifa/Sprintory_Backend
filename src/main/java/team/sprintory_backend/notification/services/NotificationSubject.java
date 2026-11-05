package team.sprintory_backend.notification.services;

import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.models.Workspace;

/**
 * What a notification is about and who caused it. Any part may be null: security notifications
 * have neither a workspace nor an actor. The snapshots stored on the notification come from here.
 */
public record NotificationSubject(Workspace workspace, Project project, Task task, User actor) {

    public static NotificationSubject task(Task task, User actor) {
        return new NotificationSubject(task.getProject().getWorkspace(), task.getProject(), task, actor);
    }

    public static NotificationSubject project(Project project, User actor) {
        return new NotificationSubject(project.getWorkspace(), project, null, actor);
    }

    public static NotificationSubject workspace(Workspace workspace, User actor) {
        return new NotificationSubject(workspace, null, null, actor);
    }

    /** No workspace, no actor: a security notification about the recipient themself. */
    public static NotificationSubject system() {
        return new NotificationSubject(null, null, null, null);
    }
}