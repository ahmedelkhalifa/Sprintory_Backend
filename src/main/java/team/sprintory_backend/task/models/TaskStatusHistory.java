package team.sprintory_backend.task.models;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import team.sprintory_backend.project.models.ProjectBoardColumn;

/** Durable audit event for a task crossing columns, including during column deletion. */
@Entity
@Table(name = "task_status_history")
@Getter
@NoArgsConstructor
public class TaskStatusHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;
    @Column(name = "from_column_id", nullable = false)
    private Long fromColumnId;
    @Column(name = "to_column_id", nullable = false)
    private Long toColumnId;
    @Column(name = "from_column_name", nullable = false, length = 100)
    private String fromColumnName;
    @Column(name = "to_column_name", nullable = false, length = 100)
    private String toColumnName;
    @Column(name = "actor_id", nullable = false)
    private Long actorId;
    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    public TaskStatusHistory(Task task, ProjectBoardColumn from, ProjectBoardColumn to, Long actorId) {
        this.task = task;
        this.fromColumnId = from.getId();
        this.toColumnId = to.getId();
        this.fromColumnName = from.getName();
        this.toColumnName = to.getName();
        this.actorId = actorId;
        this.changedAt = Instant.now();
    }
}
