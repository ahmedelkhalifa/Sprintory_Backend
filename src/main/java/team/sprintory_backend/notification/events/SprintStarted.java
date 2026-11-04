package team.sprintory_backend.notification.events;

import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.user.models.User;

/** A sprint was started (PMP-119). Every project member is notified. */
public record SprintStarted(Project project, User actor, String sprintName) {}
