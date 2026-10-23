package team.sprintory_backend.team.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** A group of workspace members (R02). Names are unique within a workspace. */
@Entity
@Table(name = "teams",
        uniqueConstraints = @UniqueConstraint(columnNames = {"workspace_id", "name"}))
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Team extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Workspace workspace;

    @Column(nullable = false, length = 100)
    private String name;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Team other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}