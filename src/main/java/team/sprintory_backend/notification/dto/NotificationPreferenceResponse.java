package team.sprintory_backend.notification.dto;

import team.sprintory_backend.notification.models.NotificationCategory;

/** One row of the preferences matrix: the effective values, whether it is locked, and the defaults. */
public record NotificationPreferenceResponse(
        NotificationCategory category,
        boolean inApp,
        boolean email,
        boolean locked,
        boolean defaultInApp,
        boolean defaultEmail
) {}