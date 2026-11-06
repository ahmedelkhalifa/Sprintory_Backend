package team.sprintory_backend.notification.events;

import java.util.Set;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.user.models.User;

/**
 * People were newly mentioned in a project chat message (PMP-137). A chat has no task, so this is
 * the project-level counterpart of {@link Mentioned}. Pass only the newly added ids: editing must
 * not notify old mentions again. The listener drops the actor and non-members.
 */
public record ProjectChatMentioned(Project project, User actor, Long messageId, Set<Long> userIds,
                                   String plainText) {

    public ProjectChatMentioned {
        userIds = (userIds == null) ? Set.of() : Set.copyOf(userIds);
    }
}