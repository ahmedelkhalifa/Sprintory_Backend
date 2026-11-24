package team.sprintory_backend.notification.events;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.user.models.User;
/** A live task lost its last open blocker. */
public record TaskUnblocked(Task task, User actor) {}
