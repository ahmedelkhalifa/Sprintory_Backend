package team.sprintory_backend.retro.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import team.sprintory_backend.user.models.User;

import java.time.Instant;

/**
 * An action item of a retro (R31): a link to the real task it created. The task holds the title,
 * status and owner (its assignee). {@code taskId} is a plain id, not an association, because a
 * soft-deleted task is hidden by {@code Task}'s {@code @SQLRestriction}; the retro still shows it
 * by {@code taskKey}. The foreign key and its cascade are in V18.
 */
@Entity
@Immutable
@Table(name = "retro_actions")
@Getter
@NoArgsConstructor
public class RetroAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "retro_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Retrospective retro;

    /** The card it came from; null when none was given (or the card row is gone). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private RetroItem item;

    @Column(name = "task_id", nullable = false, unique = true)
    private Long taskId;

    @Column(name = "task_key", nullable = false, length = 21)
    private String taskKey;

    /** Null once the account no longer exists. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public RetroAction(Retrospective retro, RetroItem item, Long taskId, String taskKey, User createdBy,
                       Instant createdAt) {
        this.retro = retro;
        this.item = item;
        this.taskId = taskId;
        this.taskKey = taskKey;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }
}
