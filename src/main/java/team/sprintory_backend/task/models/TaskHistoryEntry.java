package team.sprintory_backend.task.models;

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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/**
 * One entry of a task's activity feed (R10, docs/task-history-docs.md). Append-only:
 * {@code @Immutable} and no setters. Values and the actor's name are display snapshots taken
 * when it happened; {@code actorId} has no foreign key so the entry outlives its author.
 * Written only through {@code TaskStatusHistoryService}.
 */
@Entity
@Immutable
@Table(name = "task_history")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskHistoryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Task task;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private TaskHistoryType type;

    /** Set only for {@link TaskHistoryType#FIELD_CHANGED}. */
    @Enumerated(EnumType.STRING)
    @Column(updatable = false, length = 30)
    private TaskHistoryField field;

    @Column(name = "old_value", updatable = false, columnDefinition = "text")
    private String oldValue;

    @Column(name = "new_value", updatable = false, columnDefinition = "text")
    private String newValue;

    /** The comment or subtask the entry is about; null otherwise. */
    @Column(name = "ref_id", updatable = false)
    private Long refId;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private Long actorId;

    @Column(name = "actor_name", nullable = false, updatable = false, length = 512)
    private String actorName;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
}