package team.sprintory_backend.notification.events;

import team.sprintory_backend.user.models.User;

/** A burst of failed logins was detected for this account (LoginFailureTracker). Always notified. */
public record RepeatedLoginFailures(User user, int failedAttempts) {}
