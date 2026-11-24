package team.sprintory_backend.retro.models;

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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import team.sprintory_backend.audit.BaseEntity;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.sprint.models.Sprint;
import team.sprintory_backend.user.models.User;

import java.time.Instant;

/**
 * The retrospective of one ended sprint (R31, docs/retrospectives-docs.md). One per sprint
 * ({@code uq_retrospectives_sprint}); the title is fixed at creation. Closing is final.
 */
@Entity
@Table(name = "retrospectives")
@Getter
@Setter
@NoArgsConstructor
public class Retrospective extends BaseEntity {

    /** Longest sprint name (100) plus " Retrospective". */
    public static final int TITLE_MAX = 150;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sprint_id", nullable = false, updatable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Sprint sprint;

    @Column(nullable = false, updatable = false, length = TITLE_MAX)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RetroStatus status = RetroStatus.OPEN;

    /** Null once the account no longer exists. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", updatable = false)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User createdByUser;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** Null while open, and once the account no longer exists. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User closedByUser;

    public Retrospective(Project project, Sprint sprint, String title, User createdByUser) {
        this.project = project;
        this.sprint = sprint;
        this.title = title;
        this.createdByUser = createdByUser;
    }

    public boolean isClosed() {
        return status == RetroStatus.CLOSED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Retrospective other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
