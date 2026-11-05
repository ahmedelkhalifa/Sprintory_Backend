package team.sprintory_backend.notification.events;

import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.user.models.User;

/** A task moved to another board column. The names are the columns as they were at the move. */
public record TaskStatusChanged(Task task, User actor, String fromStatus, String toStatus) {}
