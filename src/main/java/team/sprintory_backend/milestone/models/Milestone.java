package team.sprintory_backend.milestone.models;

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
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.SQLRestriction;
import team.sprintory_backend.audit.BaseEntity;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.user.models.User;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A checkpoint with a target date that tasks are linked to (R12, docs/milestones-docs.md). Progress
 * and status are calculated from the linked tasks and never stored. Deleting is a soft delete:
 * {@code deletedAt} is set and the {@link SQLRestriction} hides the row from every JPA read.
 */
@Entity
@Table(name = "milestones")
@SQLRestriction("deleted_at is null")
@Getter
@Setter
@NoArgsConstructor
public class Milestone extends BaseEntity {

    public static final int NAME_MAX = 100;
    public static final int DESCRIPTION_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Project project;

    /** Unique per project among live milestones, ignoring case ({@code uk_milestones_project_name}). */
    @Column(nullable = false, length = NAME_MAX)
    private String name;

    /** Plain text, never rendered as HTML; null when none. */
    @Column(length = DESCRIPTION_MAX)
    private String description;

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    /** Null once the account no longer exists. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", updatable = false)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User createdByUser;

    /** Set when the milestone is deleted; the row stays so it can be restored. */
    private Instant deletedAt;

    public Milestone(Project project, String name, LocalDate targetDate, String description, User createdByUser) {
        this.project = project;
        this.name = name;
        this.targetDate = targetDate;
        this.description = description;
        this.createdByUser = createdByUser;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Milestone other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
