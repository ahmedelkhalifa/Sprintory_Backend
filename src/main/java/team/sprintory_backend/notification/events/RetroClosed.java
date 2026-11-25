package team.sprintory_backend.notification.events;

import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.sprint.models.Sprint;
import team.sprintory_backend.user.models.User;

/**
 * A sprint's retrospective was closed (PMP-44). Every project member except the closer is told;
 * the sprint is the notification's link target.
 */
public record RetroClosed(Project project, User actor, Sprint sprint) {}
