package team.sprintory_backend.notification.events;

import java.util.Set;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.user.models.User;

/**
 * Someone commented on a task (PMP-133). {@code plainText} is the comment as plain text with
 * mentions as {@code @Name}; the listener cuts it to the excerpt. {@code mentionedUserIds} are the
 * people the comment mentions, teams already expanded (PMP-136); they get MENTIONED instead of
 * TASK_COMMENTED. See docs/notifications-docs.md §15.
 */
public record TaskCommented(Task task, User actor, Long commentId, String plainText, Set<Long> mentionedUserIds) {

    public TaskCommented {
        mentionedUserIds = (mentionedUserIds == null) ? Set.of() : Set.copyOf(mentionedUserIds);
    }
}
