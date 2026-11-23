package team.sprintory_backend.jiraimport.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.task.models.Task;

/** Immutable provenance mapping; allows idempotent imports without changing generated task keys. */
@Entity
@Table(name = "jira_imported_issues", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"project_id", "jira_issue_id"}),
        @UniqueConstraint(columnNames = {"project_id", "jira_key"})})
@Getter @NoArgsConstructor
public class JiraIssueLink {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Project project;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Task task;
    @Column(name = "jira_issue_id", nullable = false, length = 60)
    private String jiraIssueId;
    @Column(name = "jira_key", nullable = false, length = 50)
    private String jiraKey;
    @Column(name = "jira_epic_key", length = 50)
    private String jiraEpicKey;
    @Column(name = "jira_created", length = 100)
    private String jiraCreated;
    @Column(name = "jira_updated", length = 100)
    private String jiraUpdated;

    public JiraIssueLink(Project project, Task task, String jiraIssueId, String jiraKey,
                         String jiraEpicKey, String jiraCreated, String jiraUpdated) {
        this.project = project; this.task = task; this.jiraIssueId = jiraIssueId;
        this.jiraKey = jiraKey; this.jiraEpicKey = jiraEpicKey;
        this.jiraCreated = jiraCreated; this.jiraUpdated = jiraUpdated;
    }
}
