package team.sprintory_backend.notification.events;

import java.util.Set;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.user.models.User;

/**
 * People were newly mentioned outside a comment, e.g. in a task description (PMP-136). Pass only
 * the newly added ids: editing must not notify old mentions again.
 */
public record Mentioned(Task task, User actor, Set<Long> userIds, String plainText) {

    public Mentioned {
        userIds = (userIds == null) ? Set.of() : Set.copyOf(userIds);
    }
}
