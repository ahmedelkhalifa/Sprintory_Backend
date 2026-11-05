package team.sprintory_backend.notification.repositories;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import team.sprintory_backend.notification.models.NotificationPreference;
import team.sprintory_backend.notification.models.NotificationPreferenceId;

public interface NotificationPreferenceRepository
        extends JpaRepository<NotificationPreference, NotificationPreferenceId> {

    /** The switches this person changed away from the defaults; empty for a new user. */
    List<NotificationPreference> findByIdUserId(Long userId);
}