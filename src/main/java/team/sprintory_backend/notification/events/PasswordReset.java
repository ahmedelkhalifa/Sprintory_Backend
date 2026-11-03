package team.sprintory_backend.notification.events;

import team.sprintory_backend.user.models.User;

/** The person set a new password through the "forgot password" link. Always notified. */
public record PasswordReset(User user) {}
