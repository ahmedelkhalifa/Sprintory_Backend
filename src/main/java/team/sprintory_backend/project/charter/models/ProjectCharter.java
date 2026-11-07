package team.sprintory_backend.project.charter.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import team.sprintory_backend.project.models.Project;

/** One charter per project. Empty until the PM decides to document it. */
@Entity
@Table(name = "project_charters")
@Getter
@Setter
@NoArgsConstructor
public class ProjectCharter {
    @Id
    @Column(name = "project_id")
    private Long projectId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(name = "in_scope", columnDefinition = "text")
    private String inScope;

    @Column(name = "out_of_scope", columnDefinition = "text")
    private String outOfScope;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public ProjectCharter(Project project) {
        this.project = project;
        this.projectId = project.getId();
    }
}
