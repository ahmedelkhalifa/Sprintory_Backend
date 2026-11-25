package team.sprintory_backend.notification.services;

import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.sprint.models.Sprint;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.models.Workspace;

/**
 * What a notification is about and who caused it. Any part may be null: security notifications
 * have neither a workspace nor an actor. The snapshots stored on the notification come from here.
 */
public record NotificationSubject(Workspace workspace, Project project, Task task, Sprint sprint, User actor) {

    public static NotificationSubject task(Task task, User actor) {
        return new NotificationSubject(task.getProject().getWorkspace(), task.getProject(), task, null, actor);
    }

    /** A sprint or retro notification (PMP-44): opening it goes to the sprint's page. */
    public static NotificationSubject sprint(Sprint sprint, User actor) {
        Project project = sprint.getProject();
        return new NotificationSubject(project.getWorkspace(), project, null, sprint, actor);
    }

    public static NotificationSubject project(Project project, User actor) {
        return new NotificationSubject(project.getWorkspace(), project, null, null, actor);
    }

    public static NotificationSubject workspace(Workspace workspace, User actor) {
        return new NotificationSubject(workspace, null, null, null, actor);
    }

    /** No workspace, no actor: a security notification about the recipient themself. */
    public static NotificationSubject system() {
        return new NotificationSubject(null, null, null, null, null);
    }
}
