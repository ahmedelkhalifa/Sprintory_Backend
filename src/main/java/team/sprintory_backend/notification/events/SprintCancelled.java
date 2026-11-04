package team.sprintory_backend.notification.events;

import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.user.models.User;

/** A sprint was cancelled (PMP-122). Every project member is notified; the reason is not sent. */
public record SprintCancelled(Project project, User actor, String sprintName) {}
