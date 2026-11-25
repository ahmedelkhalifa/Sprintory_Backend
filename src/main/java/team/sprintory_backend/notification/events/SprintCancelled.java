package team.sprintory_backend.notification.events;

import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.sprint.models.Sprint;
import team.sprintory_backend.user.models.User;

/**
 * A sprint was cancelled (PMP-122). Every project member is notified, without the reason, and the
 * other retro managers get a retrospective reminder (PMP-44).
 */
public record SprintCancelled(Project project, User actor, Sprint sprint) {}
