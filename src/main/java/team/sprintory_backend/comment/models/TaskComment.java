package team.sprintory_backend.comment.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import team.sprintory_backend.audit.BaseEntity;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.user.models.User;

import java.time.Instant;

/**
 * A comment on a task (R10, docs/comments-docs.md). The body is normalized TipTap JSON.
 * Deleting is soft and, unlike tasks, deleted comments stay listed as "Comment deleted"
 * placeholders, so there is no {@code @SQLRestriction}: check {@link #isDeleted()} instead.
 */
@Entity
@Table(name = "task_comments")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TaskComment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Task task;

    /** Null once the account no longer exists. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", updatable = false)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User author;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    /** Set when the text really changed; shown as "(edited)". */
    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Who deleted it, the author or the Project Manager; no foreign key. */
    @Column(name = "deleted_by_id")
    private Long deletedById;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** Written by the given user; false once the author's account is gone. */
    public boolean isWrittenBy(User user) {
        return author != null && author.getId().equals(user.getId());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TaskComment other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}