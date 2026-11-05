package team.sprintory_backend.notification.events;

import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.user.models.User;

/** Someone was added to a project, directly or through a team. */
public record ProjectMemberAdded(Project project, User actor, User member) {}
