package team.sprintory_backend.notification.events;

import java.util.Set;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.sprint.models.Sprint;
import team.sprintory_backend.user.models.User;

/**
 * People were newly mentioned in a retro card (PMP-44), the retro counterpart of
 * {@link ProjectChatMentioned}. Pass only the newly added ids: editing must not notify old
 * mentions again. The listener drops the actor and non-members.
 */
public record RetroMentioned(Project project, User actor, Sprint sprint, Set<Long> userIds, String plainText) {

    public RetroMentioned {
        userIds = (userIds == null) ? Set.of() : Set.copyOf(userIds);
    }
}
