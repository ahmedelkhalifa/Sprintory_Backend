package team.sprintory_backend.notification.events;

import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.user.models.User;

/**
 * A task got a new assignee (on create, or a change to another person). Publish only when the
 * assignee really changed to a non-null person; clearing it publishes nothing.
 */
public record TaskAssigned(Task task, User actor, User assignee) {}
