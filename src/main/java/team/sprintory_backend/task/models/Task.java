package team.sprintory_backend.task.models;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.SQLRestriction;
import team.sprintory_backend.audit.BaseEntity;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectBoardColumn;
import team.sprintory_backend.user.models.User;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * A task inside a project (R09). Deleting is a soft delete: {@code deletedAt} is set and the
 * {@link SQLRestriction} hides the row from every JPA read. Native SQL must filter
 * {@code deleted_at is null} itself.
 */
@Entity
@Table(name = "tasks",
        uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "task_number"}))
@SQLRestriction("deleted_at is null")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Task extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Project project;

    /** Per-project sequence number, from {@code Project.lastTaskNumber}; never reused. */
    @Column(name = "task_number", nullable = false, updatable = false)
    private int number;

    /** Project key + number, e.g. {@code MOB-42}. Stored so it can be searched. */
    @Column(name = "task_key", nullable = false, updatable = false, length = 21)
    private String key;

    @Column(nullable = false, length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskPriority priority;

    /**
     * The board column the task is in. No cascade on purpose: removing a column must never
     * delete tasks silently. Deleting the whole project still works, see V6.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "status_column_id", nullable = false)
    private ProjectBoardColumn status;

    /** Optional; a member of the project when assigned. Cleared if the user is deleted. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User assignee;

    /** Story points, 0 or more; null when not estimated. */
    private Integer estimate;

    @Column(columnDefinition = "text")
    private String description;

    /** Order inside the status column; lower comes first. Internal, never sent to clients. */
    @Column(name = "board_position", nullable = false)
    private long boardPosition;

    /** Order in the product backlog (Scrum); lower comes first. Internal, never sent to clients. */
    @Column(name = "backlog_rank", nullable = false)
    private long backlogRank;

    /** Free-text tags; batch-loaded so a page of tasks does not run one query per row. */
    @Builder.Default
    @ElementCollection
    @CollectionTable(name = "task_labels", joinColumns = @JoinColumn(name = "task_id", nullable = false))
    @OnDelete(action = OnDeleteAction.CASCADE)
    @Column(name = "label", nullable = false, length = 50)
    @BatchSize(size = 100)
    private Set<String> labels = new HashSet<>();

    /** Set when the task is deleted; the row stays in the database. */
    private Instant deletedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Task other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}