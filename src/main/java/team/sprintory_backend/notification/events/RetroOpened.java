package team.sprintory_backend.notification.events;

import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.sprint.models.Sprint;
import team.sprintory_backend.user.models.User;

/**
 * A sprint's retrospective was created (PMP-44); not published when it already existed. Every
 * project member except the creator is told.
 */
public record RetroOpened(Project project, User actor, Sprint sprint) {}
