package team.sprintory_backend.common.dto;

import team.sprintory_backend.audit.models.AuditTarget;
import team.sprintory_backend.user.models.User;

/**
 * A person in a response: only the id and the display name, never the {@code User} entity (it
 * carries the email and password hash). Meant to replace the feature-specific copies
 * ({@code RetroUser}, {@code CommentResponse.Author}, ...) over time.
 */
public record UserRef(Long id, String name) {

    /** Null when there is no person, e.g. the account no longer exists. */
    public static UserRef of(User user) {
        return user == null ? null : new UserRef(user.getId(), AuditTarget.displayName(user));
    }
}
