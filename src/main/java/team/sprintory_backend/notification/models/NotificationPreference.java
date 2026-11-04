package team.sprintory_backend.notification.models;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import team.sprintory_backend.user.models.User;

/**
 * One switch a person changed away from its default. Absent rows mean "the default of
 * {@link NotificationCategory}", which is what lets a new user start with no setup.
 */
@Entity
@Table(name = "notification_preferences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationPreference {

    @EmbeddedId
    private NotificationPreferenceId id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Setter
    @Column(nullable = false)
    private boolean enabled;

    public NotificationPreference(User user, NotificationCategory category, NotificationChannel channel,
                                  boolean enabled) {
        this.id = new NotificationPreferenceId(user.getId(), category, channel);
        this.user = user;
        this.enabled = enabled;
    }
}