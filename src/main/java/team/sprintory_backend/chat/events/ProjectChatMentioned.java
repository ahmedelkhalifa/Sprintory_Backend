package team.sprintory_backend.chat.events;

import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.user.models.User;

import java.util.Set;

/**
 * Newly mentioned project members in one project-chat message.
 * Chat publishes this in the write transaction; the notification module owns delivery.
 * Recipients are already validated/expanded by MentionService. A listener must still
 * exclude the actor and members who lose access before delivery.
 */
public record ProjectChatMentioned(Project project, User actor, Long messageId,
                                   Set<Long> userIds, String plainText) {
    public ProjectChatMentioned {
        userIds = userIds == null ? Set.of() : Set.copyOf(userIds);
    }
}
