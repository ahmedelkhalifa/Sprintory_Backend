package team.sprintory_backend.notification.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.models.Workspace;

/**
 * One in-app notification for one person (R27). Names are snapshots taken when it was created;
 * project, task and actor are plain ids without a foreign key, so the notification outlives them
 * and opening it then answers 404. Only {@code readAt} ever changes.
 * Contract: docs/notifications-docs.md.
 */
@Entity
@Table(name = "notifications")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 40)
    private NotificationType type;

    /** Null only for security notifications. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id", updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Workspace workspace;

    @Column(name = "workspace_name", updatable = false, length = 100)
    private String workspaceName;

    @Column(name = "project_id", updatable = false)
    private Long projectId;

    @Column(name = "project_key", updatable = false, length = 10)
    private String projectKey;

    @Column(name = "project_name", updatable = false, length = 100)
    private String projectName;

    @Column(name = "task_id", updatable = false)
    private Long taskId;

    @Column(name = "task_key", updatable = false, length = 21)
    private String taskKey;

    @Column(name = "task_title", updatable = false)
    private String taskTitle;

    /** Null when the system is the source (security notifications); then the name is null too. */
    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Column(name = "actor_name", updatable = false, length = 512)
    private String actorName;

    @Column(nullable = false, updatable = false, length = 1000)
    private String message;

    /** Plain text, at most 200 characters; only for comments and mentions. */
    @Column(updatable = false, length = 300)
    private String excerpt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Notification other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}