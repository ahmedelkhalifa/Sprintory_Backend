package team.sprintory_backend.sprint.models;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity @Table(name = "sprint_scope_changes")
@Getter @NoArgsConstructor
public class SprintScopeChange {
    public enum Action { ADDED, REMOVED }
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
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Action action;
    @Column(name = "actor_id", nullable = false)
    private Long actorId;
    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    public SprintScopeChange(Sprint sprint, Long taskId, String taskKey, Action action, Long actorId) {
        this.sprint = sprint;
        this.taskId = taskId;
        this.taskKey = taskKey;
        this.action = action;
        this.actorId = actorId;
        this.changedAt = Instant.now();
    }
}
