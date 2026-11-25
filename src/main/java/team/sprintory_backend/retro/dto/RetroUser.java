package team.sprintory_backend.retro.dto;

import team.sprintory_backend.audit.models.AuditTarget;
import team.sprintory_backend.user.models.User;

/**
 * A person in a retro response: creator, closer, card author or action owner. Only the id and
 * the display name, never the {@code User} entity (it carries the email and password hash).
 */
public record RetroUser(Long id, String name) {

    /** Null when there is no person, e.g. the account no longer exists. */
    public static RetroUser of(User user) {
        return user == null ? null : new RetroUser(user.getId(), AuditTarget.displayName(user));
    }
}
