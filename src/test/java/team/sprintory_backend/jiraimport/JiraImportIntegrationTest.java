package team.sprintory_backend.jiraimport;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.AbstractIntegrationTest;
import team.sprintory_backend.comment.repositories.TaskCommentRepository;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.jiraimport.repositories.JiraIssueLinkRepository;
import team.sprintory_backend.jiraimport.repositories.JiraSprintLinkRepository;
import team.sprintory_backend.jiraimport.services.JiraImportService;
import team.sprintory_backend.project.dto.CreateProjectRequest;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.repositories.ProjectBoardColumnRepository;
import team.sprintory_backend.project.services.ProjectService;
import team.sprintory_backend.sprint.models.SprintStatus;
import team.sprintory_backend.sprint.models.Sprint;
import team.sprintory_backend.sprint.services.SprintService;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.sprint.repositories.SprintRepository;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;
import team.sprintory_backend.workspace.repositories.WorkspaceRepository;

import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** End-to-end against disposable Postgres + Flyway V17; never uses a developer's local database. */
@Transactional
@TestPropertySource(properties = "sprintory.jira-import.enabled=true")
class JiraImportIntegrationTest extends AbstractIntegrationTest {
    @Autowired private JiraImportService importer;
    @Autowired private ProjectService projectService;
    @Autowired private JiraIssueLinkRepository issueLinks;
    @Autowired private JiraSprintLinkRepository sprintLinks;
    @Autowired private TaskRepository tasks;
    @Autowired private TaskCommentRepository comments;
    @Autowired private SprintRepository sprints;
    @Autowired private SprintService sprintService;
    @Autowired private ProjectRepository projects;
    @Autowired private ProjectBoardColumnRepository columns;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository workspaceMembers;
    @Autowired private EntityManager em;
    private User pm;
    private Long projectId;

    @BeforeEach void setup() {
        pm = users.save(User.builder().firstName("Jira").lastName("Tester")
                .email("jira-import-test@example.invalid").enabled(true).build());
        Workspace workspace = workspaces.save(Workspace.builder().name("Jira import test")
                .slug("jira-import-test").build());
        workspaceMembers.save(WorkspaceMember.builder().workspace(workspace).user(pm)
                .role(Role.WORKSPACE_MEMBER).build());
        projectId = projectService.create(pm, workspace.getId(),
                new CreateProjectRequest("Imported work", "IMP", ProjectTemplate.SCRUM, null)).id();
    }

    @Test void previewHasNoWritesAndReportsSkips() {
        var preview = importer.preview(pm, projectId, csv(), sprintJson());
        assertTrue(preview.ready(), preview.blockers().toString());
        assertEquals(5, preview.sourceIssues());
        assertEquals(1, preview.epicsSkipped());
        assertEquals(1, preview.orphanSubtasksSkipped());
        assertEquals(2, preview.newTopLevelItems());
        assertEquals(1, preview.newSubtasks());
        assertEquals(2, preview.newSprints());
        assertEquals(0, issueLinks.findByProjectId(projectId).size());
        assertEquals(0, sprintLinks.findByProjectId(projectId).size());
        assertEquals(0, tasks.count());
    }

    @Test void importsSprintsHierarchyMappingCommentsAndRankAndIsIdempotent() {
        var result = importer.execute(pm, projectId, csv(), sprintJson(),
                "{\"jira-user-1\":" + pm.getId() + "}", "IMPORT");
        assertEquals(2, result.createdTopLevel());
        assertEquals(1, result.createdSubtasks());
        assertEquals(2, result.createdSprints());
        assertEquals(1, result.commentsImported());
        assertEquals(3, issueLinks.findByProjectId(projectId).size());
        assertTrue(importer.mappings(pm, projectId).stream().anyMatch(m ->
                m.jiraKey().equals("PMP-11") && m.jiraEpicKey().equals("PMP-10")
                && m.sprintoryKey().startsWith("IMP-")));
        assertEquals(2, sprintLinks.findByProjectId(projectId).size());
        assertEquals(2, sprints.findByProjectIdOrderByNumberAsc(projectId).size());
        var importedSprints = sprints.findByProjectIdOrderByNumberAsc(projectId);
        assertEquals(SprintStatus.COMPLETED, importedSprints.get(0).getStatus());
        assertEquals(SprintStatus.ACTIVE, importedSprints.get(1).getStatus());
        assertNull(importedSprints.get(0).getCommittedPoints());
        assertNull(importedSprints.get(0).getCompletedPoints());
        assertNull(importedSprints.get(1).getCommittedPoints());
        var history = sprintService.history(pm, projectId);
        assertEquals(1, history.size());
        assertEquals(SprintStatus.COMPLETED, history.get(0).status());
        assertNull(history.get(0).completedPoints());

        var links = issueLinks.findByProjectId(projectId);
        Task story = links.stream().filter(l -> l.getJiraKey().equals("PMP-11")).findFirst().orElseThrow().getTask();
        Task subtask = links.stream().filter(l -> l.getJiraKey().equals("PMP-12")).findFirst().orElseThrow().getTask();
        Task task = links.stream().filter(l -> l.getJiraKey().equals("PMP-14")).findFirst().orElseThrow().getTask();
        assertEquals("PMP-10", links.stream().filter(l -> l.getJiraKey().equals("PMP-11"))
                .findFirst().orElseThrow().getJiraEpicKey());
        assertEquals(story.getId(), subtask.getParent().getId());
        assertEquals(importedSprints.get(0).getId(), story.getSprint().getId());
        assertEquals(importedSprints.get(1).getId(), task.getSprint().getId());
        assertEquals(pm.getId(), story.getAssignee().getId());
        assertTrue(story.getLabels().contains("second"));
        assertEquals(5, story.getEstimate());
        assertNull(subtask.getSprint()); // Children inherit the parent's sprint.
        assertTrue(story.getDescription().contains("Line one"));
        assertEquals(1, comments.findByTaskIdOrderByCreatedAtAscIdAsc(story.getId(),
                org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements());
        assertEquals("In Review", task.getStatus().getName());
        assertTrue(story.getBacklogRank() < task.getBacklogRank());
        assertTrue(columns.findByBoardProjectIdOrderByPositionAscIdAsc(projectId).stream()
                .anyMatch(c -> c.getName().equals("In Review")));

        var second = importer.execute(pm, projectId, csv(), sprintJson(), "", "IMPORT");
        assertEquals(0, second.createdTopLevel());
        assertEquals(0, second.createdSubtasks());
        assertEquals(0, second.createdSprints());
        assertEquals(3, second.skippedExisting());
        em.flush();
        assertEquals(3, issueLinks.findByProjectId(projectId).size());
    }

    @Test void csvOnlyImportPreservesHierarchyAndStatusButCreatesNoSprints() {
        var preview = importer.preview(pm, projectId, csv(), null);
        assertTrue(preview.ready(), preview.blockers().toString());
        assertEquals(0, preview.newSprints());
        assertEquals(2, preview.newTopLevelItems());
        assertEquals(1, preview.newSubtasks());
        assertTrue(preview.warnings().stream().anyMatch(s -> s.contains("backlog")));
        assertEquals(0, tasks.count());
        var result = importer.execute(pm, projectId, csv(), null, "", "IMPORT");
        assertEquals(2, result.createdTopLevel());
        assertEquals(1, result.createdSubtasks());
        assertEquals(0, result.createdSprints());
        assertEquals(1, result.commentsImported());
        assertTrue(sprints.findByProjectIdOrderByNumberAsc(projectId).isEmpty());
        assertTrue(sprintLinks.findByProjectId(projectId).isEmpty());
        var links = issueLinks.findByProjectId(projectId);
        assertEquals(3, links.size());
        assertTrue(links.stream().allMatch(l -> l.getTask().getSprint() == null
                && l.getTask().getSprintPosition() == null));
        Task parent = links.stream().filter(l -> l.getJiraKey().equals("PMP-11")).findFirst().orElseThrow().getTask();
        Task child = links.stream().filter(l -> l.getJiraKey().equals("PMP-12")).findFirst().orElseThrow().getTask();
        assertEquals(parent.getId(), child.getParent().getId());
        assertEquals("Done", parent.getStatus().getName());
        var repeat = importer.execute(pm, projectId, csv(), null, "", "IMPORT");
        assertEquals(0, repeat.createdTopLevel());
        assertEquals(0, repeat.createdSubtasks());
        assertEquals(0, repeat.createdSprints());
        assertEquals(3, repeat.skippedExisting());
        assertFalse(importer.preview(pm, projectId, csv(), sprintJson()).ready(),
                "One-time migration cannot be augmented with sprints later");
    }

    @Test void suppliedIncompleteOrInvalidSprintJsonIsNeverSilentlyIgnored() {
        for (String json : List.of(
                "{\"isLast\":false,\"values\":[]}",
                "{\"startAt\":50,\"isLast\":true,\"values\":[]}",
                "{\"total\":4,\"values\":[]}",
                "{\"isLast\":\"true\",\"values\":[]}",
                "{\"startAt\":-1,\"values\":[]}",
                "", "not JSON", "null")) {
            byte[] invalid = json.getBytes(StandardCharsets.UTF_8);
            assertThrows(BadRequestException.class, () -> importer.preview(pm, projectId, csv(), invalid));
            assertThrows(BadRequestException.class, () -> importer.execute(pm, projectId, csv(), invalid, "", "IMPORT"));
        }
        var missingNames = importer.preview(pm, projectId, csv(),
                "{\"isLast\":true,\"startAt\":0,\"total\":0,\"values\":[]}".getBytes(StandardCharsets.UTF_8));
        assertFalse(missingNames.ready());
        assertTrue(missingNames.blockers().stream().anyMatch(s -> s.contains("Missing sprint metadata")));
        assertEquals(0, tasks.count());
        assertEquals(0, sprintLinks.findByProjectId(projectId).size());
    }

    @Test void completePagedExportIsAccepted() {
        byte[] complete = new String(sprintJson(), StandardCharsets.UTF_8)
                .replace("{\"values\":", "{\"isLast\":true,\"startAt\":0,\"total\":2,\"values\":")
                .getBytes(StandardCharsets.UTF_8);
        var preview = importer.preview(pm, projectId, csv(), complete);
        assertTrue(preview.ready(), preview.blockers().toString());
        assertEquals(2, preview.newSprints());
    }

    @Test void completeExportCanIncludeUndatedFutureSprint() {
        String json = new String(sprintJson(), StandardCharsets.UTF_8)
                .replace("{\"values\":", "{\"isLast\":true,\"startAt\":0,\"total\":3,\"values\":")
                .replace("]}", ",{\"id\":39,\"name\":\"Sprint 3\",\"state\":\"future\"}]}");
        var result = importer.execute(pm, projectId, csv(), json.getBytes(StandardCharsets.UTF_8), "", "IMPORT");
        assertEquals(3, result.createdSprints());
        Sprint future = sprints.findByProjectIdOrderByNumberAsc(projectId).get(2);
        assertEquals(SprintStatus.PLANNED, future.getStatus());
        assertNull(future.getStartDate());
        assertNull(future.getEndDate());
    }

    @Test void invalidUserMappingsAbortWithoutWrites() {
        assertThrows(BadRequestException.class,
                () -> importer.execute(pm, projectId, csv(), sprintJson(), "{\"jira-user-1\":999999}", "IMPORT"));
        assertEquals(0, tasks.count());
        assertEquals(0, sprintLinks.findByProjectId(projectId).size());
    }

    @Test void cannotImportIntoExistingNonEmptyProject() {
        sprints.save(new Sprint(projects.findById(projectId).orElseThrow(), 1,
                "Already planned", "", null,
                java.time.LocalDate.of(2026, 12, 1), java.time.LocalDate.of(2026, 12, 14)));
        var preview = importer.preview(pm, projectId, csv(), sprintJson());
        assertFalse(preview.ready());
        assertTrue(preview.blockers().stream().anyMatch(s -> s.contains("empty Scrum project")));
        assertThrows(BadRequestException.class,
                () -> importer.execute(pm, projectId, csv(), sprintJson(), "", "IMPORT"));
        assertEquals(0, issueLinks.findByProjectId(projectId).size());
    }

    @Test void cannotAppendNewJiraIssuesAfterOneTimeMigration() {
        importer.execute(pm, projectId, csv(), sprintJson(), "", "IMPORT");
        String newCsv = new String(csv(), StandardCharsets.UTF_8)
                + row("15", "PMP-15", "PMP", "Story", "New issue after migration", "To Do", "Medium",
                  "", "", "", "", "", "", "", "0|f", "", "", "", "", "") + "\n";
        var preview = importer.preview(pm, projectId, newCsv.getBytes(StandardCharsets.UTF_8), sprintJson());
        assertFalse(preview.ready());
        assertTrue(preview.blockers().stream().anyMatch(s -> s.contains("already been migrated")));
        assertThrows(BadRequestException.class,
                () -> importer.execute(pm, projectId, newCsv.getBytes(StandardCharsets.UTF_8),
                    sprintJson(), "", "IMPORT"));
        assertEquals(3, issueLinks.findByProjectId(projectId).size());
    }

    private static byte[] sprintJson() {
        return ("""
                {"values":[
                  {"id":3,"name":"Sprint 1","state":"closed","startDate":"2026-10-18T03:00:00.000Z",
                   "endDate":"2026-11-01T03:00:00.000Z","completeDate":"2026-11-01T12:41:12.283Z","goal":"First"},
                  {"id":6,"name":"Sprint 2","state":"active","startDate":"2026-11-02T18:53:00.000Z",
                   "endDate":"2026-11-16T18:53:00.000Z","goal":"Second"}
                ]}
                """).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] csv() {
        String header = "Issue id,Issue key,Project key,Issue Type,Summary,Status,Priority,Assignee Id,Assignee,"
                + "Parent,Parent key,Sprint,Description,Custom field (Story point estimate),"
                + "Custom field (Rank),Labels,Labels,Comment,Created,Updated\n";
        String body = String.join("\n",
                row("10", "PMP-10", "PMP", "Epic", "Epic title", "To Do", "Medium", "", "", "", "", "", "", "", "0|a", "", "", "", "", ""),
                row("11", "PMP-11", "PMP", "Story", "Top-level", "Done", "Highest", "jira-user-1", "Jira Tester", "10", "PMP-10", "Sprint 1", "Line one\nLine two", "5.0", "0|b", "first", "second", "05/Nov/26 8:16 PM;jira-user-1;Nice!", "", ""),
                row("12", "PMP-12", "PMP", "Subtask", "Child", "Done", "Medium", "jira-user-1", "Jira Tester", "11", "PMP-11", "Sprint 1", "", "", "0|c", "", "", "", "", ""),
                row("13", "PMP-13", "PMP", "Subtask", "Orphan", "To Do", "Medium", "", "", "", "", "", "", "", "0|d", "", "", "", "", ""),
                row("14", "PMP-14", "PMP", "Task", "Review", "In Review", "High", "", "", "", "", "Sprint 2", "", "3.0", "0|e", "", "", "", "", ""));
        return (header + body + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String row(String... values) {
        return String.join(",", java.util.Arrays.stream(values)
                .map(v -> "\"" + v.replace("\"", "\"\"") + "\"").toList());
    }
}
