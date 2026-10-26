package team.sprintory_backend.project.models;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import team.sprintory_backend.audit.BaseEntity;
import team.sprintory_backend.workspace.models.Workspace;

/**
 * A project inside a workspace (R02). Story 6 adds the Scrum/Kanban template, because the
 * roles a project offers depend on it; story 8 (R06) adds the key and the board
 * ({@link ProjectBoard}).
 */
@Entity
@Table(name = "projects",
        uniqueConstraints = @UniqueConstraint(columnNames = {"workspace_id", "project_key"}))
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Project extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Workspace workspace;

    @Column(nullable = false, length = 100)
    private String name;

    /**
     * Upper-case, unique within the workspace, never changes: task keys (story 9) are built from
     * it. The column is {@code project_key} because {@code key} is an SQL keyword.
     */
    @Column(name = "project_key", nullable = false, updatable = false, length = 10)
    private String key;

    /** Optional, no length limit; null when not set. */
    @Column(columnDefinition = "text")
    private String description;

    /** Chosen at creation; never changes. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ProjectTemplate template;

    /**
     * The number of the project's latest task, so the next one is {@code KEY-(n+1)} (story 9).
     * Never decreases, so task keys are never reused. Only {@code ProjectRepository
     * .incrementTaskNumber} changes it: {@code updatable = false} keeps a save of this entity from
     * writing back a stale value.
     */
    @Column(name = "last_task_number", nullable = false, updatable = false)
    private int lastTaskNumber;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Project other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}