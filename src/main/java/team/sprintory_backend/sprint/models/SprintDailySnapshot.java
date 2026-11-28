package team.sprintory_backend.sprint.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A sprint's totals at the end of one day (UTC), read by the burndown (PMP-42,
 * docs/burndown-docs.md §5). Written only by the native upsert in
 * {@code SprintDailySnapshotRepository}, so there is no constructor.
 */
@Entity
@Table(name = "sprint_daily_snapshots",
        uniqueConstraints = @UniqueConstraint(columnNames = {"sprint_id", "snapshot_date"}))
@Getter @NoArgsConstructor
public class SprintDailySnapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sprint_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Sprint sprint;
    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;
    @Column(name = "total_points", nullable = false)
    private int totalPoints;
    @Column(name = "remaining_points", nullable = false)
    private int remainingPoints;
    @Column(name = "total_tasks", nullable = false)
    private int totalTasks;
    @Column(name = "remaining_tasks", nullable = false)
    private int remainingTasks;
    @Column(name = "unestimated_tasks", nullable = false)
    private int unestimatedTasks;
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;
}
