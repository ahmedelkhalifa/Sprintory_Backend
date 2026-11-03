package team.sprintory_backend.notification.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import team.sprintory_backend.notification.models.NotificationCategory;

/**
 * A partial change of the matrix: only the listed categories change, and inside one a null
 * channel is left as it is. Duplicate and locked categories are refused by the service.
 */
public record UpdateNotificationPreferencesRequest(@NotEmpty List<@NotNull @Valid Change> preferences) {

    /** Wrapper types on purpose: Jackson 3 rejects a missing primitive, and null means "unchanged". */
    public record Change(@NotNull NotificationCategory category, Boolean inApp, Boolean email) {}
}