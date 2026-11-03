package team.sprintory_backend.notification.services;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.notification.dto.NotificationPreferenceResponse;
import team.sprintory_backend.notification.dto.NotificationPreferencesResponse;
import team.sprintory_backend.notification.dto.UpdateNotificationPreferencesRequest;
import team.sprintory_backend.notification.models.NotificationCategory;
import team.sprintory_backend.notification.models.NotificationChannel;
import team.sprintory_backend.notification.models.NotificationPreference;
import team.sprintory_backend.notification.models.NotificationPreferenceId;
import team.sprintory_backend.notification.repositories.NotificationPreferenceRepository;
import team.sprintory_backend.user.models.User;

/**
 * Each person's category × channel matrix (docs/notifications-docs.md §6). Only switches that
 * differ from the default are stored: a value set back to its default deletes its row, so a
 * person who never changed anything has no rows and gets the defaults of {@link NotificationCategory}.
 */
@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository repository;

    /** Whether this person receives this category in this channel. Locked categories are always on. */
    @Transactional(readOnly = true)
    public boolean isEnabled(Long userId, NotificationCategory category, NotificationChannel channel) {
        if (category.locked()) {
            return true;
        }
        return repository.findById(new NotificationPreferenceId(userId, category, channel))
                .map(NotificationPreference::isEnabled)
                .orElse(category.isDefaultEnabled(channel));
    }

    @Transactional(readOnly = true)
    public NotificationPreferencesResponse get(User user) {
        Map<NotificationCategory, Map<NotificationChannel, Boolean>> overrides =
                new EnumMap<>(NotificationCategory.class);
        for (NotificationPreference stored : repository.findByIdUserId(user.getId())) {
            overrides.computeIfAbsent(stored.getId().getCategory(), c -> new EnumMap<>(NotificationChannel.class))
                    .put(stored.getId().getChannel(), stored.isEnabled());
        }

        List<NotificationPreferenceResponse> rows = new ArrayList<>();
        for (NotificationCategory category : NotificationCategory.values()) {
            Map<NotificationChannel, Boolean> mine = overrides.getOrDefault(category, Map.of());
            // A locked category ignores any stray row: it is on, full stop.
            boolean inApp = category.locked() || mine.getOrDefault(NotificationChannel.IN_APP, category.defaultInApp());
            boolean email = category.locked() || mine.getOrDefault(NotificationChannel.EMAIL, category.defaultEmail());
            rows.add(new NotificationPreferenceResponse(category, inApp, email, category.locked(),
                    category.defaultInApp(), category.defaultEmail()));
        }
        return new NotificationPreferencesResponse(rows);
    }

    /**
     * Applies the listed changes and returns the whole matrix. Everything is checked before
     * anything is written, so a refused request changes nothing.
     */
    @Transactional
    public NotificationPreferencesResponse update(User user, UpdateNotificationPreferencesRequest request) {
        Set<NotificationCategory> seen = EnumSet.noneOf(NotificationCategory.class);
        for (UpdateNotificationPreferencesRequest.Change change : request.preferences()) {
            if (!seen.add(change.category())) {
                throw new BadRequestException("Category " + change.category() + " appears more than once.");
            }
            if (change.category().locked()
                    && (Boolean.FALSE.equals(change.inApp()) || Boolean.FALSE.equals(change.email()))) {
                throw new BadRequestException(label(change.category()) + " notifications cannot be turned off.");
            }
        }

        for (UpdateNotificationPreferencesRequest.Change change : request.preferences()) {
            if (change.category().locked()) {
                continue; // only "true" gets here, which is what a locked category already is
            }
            apply(user, change.category(), NotificationChannel.IN_APP, change.inApp());
            apply(user, change.category(), NotificationChannel.EMAIL, change.email());
        }
        return get(user);
    }

    private void apply(User user, NotificationCategory category, NotificationChannel channel, Boolean value) {
        if (value == null) {
            return;
        }
        NotificationPreferenceId id = new NotificationPreferenceId(user.getId(), category, channel);
        if (value == category.isDefaultEnabled(channel)) {
            repository.deleteById(id);
            return;
        }
        repository.findById(id).ifPresentOrElse(
                stored -> stored.setEnabled(value),
                () -> repository.save(new NotificationPreference(user, category, channel, value)));
    }

    /** {@code PROJECT_MEMBERSHIP} → {@code Project membership}. */
    private static String label(NotificationCategory category) {
        String words = category.name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}