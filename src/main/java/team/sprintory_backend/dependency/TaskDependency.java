package team.sprintory_backend.dependency;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** Scalar endpoint IDs deliberately retain edges whose tasks are soft-deleted. */
@Entity
@Table(name = "task_dependency")
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskDependency {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;
    @Column(name = "blocker_task_id", nullable = false, updatable = false)
    private Long blockerId;
    @Column(name = "blocked_task_id", nullable = false, updatable = false)
    private Long blockedId;
    @Column(name = "created_by", updatable = false)
    private Long createdBy;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public TaskDependency(Long projectId, Long blockerId, Long blockedId, Long createdBy) {
        this.projectId = projectId;
        this.blockerId = blockerId;
        this.blockedId = blockedId;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }
}
