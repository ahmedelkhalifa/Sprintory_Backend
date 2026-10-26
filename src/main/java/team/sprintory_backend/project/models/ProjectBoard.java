package team.sprintory_backend.project.models;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import team.sprintory_backend.audit.BaseEntity;

/** One persistent board per project; its workflow columns are separate rows. */
@Entity
@Table(name = "project_boards")
@Getter
@Setter
@NoArgsConstructor
public class ProjectBoard extends BaseEntity {
    @Id
    private Long projectId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Project project;

    public ProjectBoard(Project project) {
        this.project = project;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProjectBoard other)) return false;
        return projectId != null && projectId.equals(other.projectId);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
