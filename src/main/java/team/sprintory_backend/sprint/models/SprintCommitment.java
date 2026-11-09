package team.sprintory_backend.sprint.models;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Immutable at sprint start, including original point estimates, even if a task changes later. */
@Entity @Table(name = "sprint_commitments", uniqueConstraints = @UniqueConstraint(columnNames = {"sprint_id", "task_id"}))
@Getter @NoArgsConstructor
public class SprintCommitment {
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
    @Column(name = "committed_estimate")
    private Integer committedEstimate;
    @Column(name = "item_order", nullable = false)
    private int position;

    public SprintCommitment(Sprint sprint, Long taskId, String taskKey, Integer estimate, int position) {
        this.sprint = sprint;
        this.taskId = taskId;
        this.taskKey = taskKey;
        this.committedEstimate = estimate;
        this.position = position;
    }
}
