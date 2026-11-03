package team.sprintory_backend.notification.events;

import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.user.models.User;

/** A task was created. The creator starts watching it; nobody is notified. */
public record TaskCreated(Task task, User actor) {}
