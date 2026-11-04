package team.sprintory_backend.notification.dto;

import java.util.List;

/** The whole matrix: always every category, in the order of {@code NotificationCategory}. */
public record NotificationPreferencesResponse(List<NotificationPreferenceResponse> preferences) {}