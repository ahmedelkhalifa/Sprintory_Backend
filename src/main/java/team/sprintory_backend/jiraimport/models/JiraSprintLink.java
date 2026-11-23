package team.sprintory_backend.jiraimport.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.sprint.models.Sprint;

@Entity
@Table(name = "jira_imported_sprints", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"project_id", "jira_sprint_id"})})
@Getter @NoArgsConstructor
public class JiraSprintLink {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Project project;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sprint_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Sprint sprint;
    @Column(name = "jira_sprint_id", nullable = false)
    private Long jiraSprintId;
    public JiraSprintLink(Project project, Sprint sprint, Long jiraSprintId) {
        this.project = project; this.sprint = sprint; this.jiraSprintId = jiraSprintId;
    }
}
