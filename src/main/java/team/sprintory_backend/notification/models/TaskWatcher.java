package team.sprintory_backend.notification.models;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.user.models.User;

/**
 * A person watching a task: they are told about its comments and status changes. Unwatching
 * deletes the row; a new assignment or comment creates it again. Rows of a project are deleted
 * when the person leaves it (docs/notifications-docs.md §7).
 */
@Entity
@Table(name = "task_watchers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskWatcher {

    @EmbeddedId
    private TaskWatcherId id;

    @MapsId("taskId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Task task;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public TaskWatcher(Task task, User user, Instant createdAt) {
        this.id = new TaskWatcherId(task.getId(), user.getId());
        this.task = task;
        this.user = user;
        this.createdAt = createdAt;
    }
}