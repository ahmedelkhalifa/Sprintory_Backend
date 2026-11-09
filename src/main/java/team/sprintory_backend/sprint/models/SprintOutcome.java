package team.sprintory_backend.sprint.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * One top-level item's result, frozen when its sprint is closed or cancelled (PMP-41). Later
 * edits to the task never change it, so velocity and reports stay stable. {@code taskId} has no
 * foreign key on purpose, like {@link SprintCommitment}.
 */
@Entity
@Immutable
@Table(name = "sprint_outcomes", uniqueConstraints = @UniqueConstraint(columnNames = {"sprint_id", "task_id"}))
@Getter @NoArgsConstructor
public class SprintOutcome {

    public enum Result { DONE, CARRIED_OVER }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sprint_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Sprint sprint;
    @Column(name = "task_id", nullable = false)
    private Long taskId;
    @Column(name = "task_key", nullable = false, length = 21)
    private String taskKey;
    /** Story points at the end; null when the item was not estimated. */
    private Integer estimate;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Result outcome;
    /** Where a carried-over item went; null means the product backlog (and for DONE items). */
    @Column(name = "destination_sprint_id")
    private Long destinationSprintId;
    @Column(name = "item_order", nullable = false)
    private int position;

    public SprintOutcome(Sprint sprint, Long taskId, String taskKey, Integer estimate, Result outcome,
                         Long destinationSprintId, int position) {
        this.sprint = sprint;
        this.taskId = taskId;
        this.taskKey = taskKey;
        this.estimate = estimate;
        this.outcome = outcome;
        this.destinationSprintId = destinationSprintId;
        this.position = position;
    }
}