package team.sprintory_backend.milestone;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import team.sprintory_backend.AbstractIntegrationTest;
import team.sprintory_backend.milestone.dto.MilestoneProgress;
import team.sprintory_backend.milestone.dto.MilestoneRequest;
import team.sprintory_backend.milestone.dto.MilestoneResponse;
import team.sprintory_backend.milestone.dto.MilestoneTasksRequest;
import team.sprintory_backend.milestone.dto.TaskMilestoneRequest;
import team.sprintory_backend.milestone.models.MilestoneStatus;
import team.sprintory_backend.milestone.services.MilestoneService;
import team.sprintory_backend.project.dto.CreateProjectRequest;
import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.project.models.ProjectMember;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectBoardService;
import team.sprintory_backend.project.services.ProjectService;
import team.sprintory_backend.security.auth.AuthCookieFactory;
import team.sprintory_backend.security.jwt.JwtService;
import team.sprintory_backend.task.api.TaskListOrder;
import team.sprintory_backend.task.api.TaskSearchFilters;
import team.sprintory_backend.task.dto.CreateSubtaskRequest;
import team.sprintory_backend.task.dto.CreateTaskRequest;
import team.sprintory_backend.task.dto.TaskResponse;
import team.sprintory_backend.task.dto.UpdateTaskRequest;
import team.sprintory_backend.task.models.TaskType;
import team.sprintory_backend.task.services.SubtaskService;
import team.sprintory_backend.task.services.TaskApiService;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;
import team.sprintory_backend.workspace.repositories.WorkspaceRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** docs/milestones-docs.md end to end on PostgreSQL: V21, the queries, the unlink on delete, and the HTTP errors. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MilestoneIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate FUTURE = LocalDate.now().plusDays(30);

    @Value("${local.server.port}") private int port;
    @Autowired private JwtService jwt;
    @Autowired private MilestoneService milestones;
    @Autowired private TaskApiService tasks;
    @Autowired private SubtaskService subtasks;
    @Autowired private ProjectService projects;
    @Autowired private ProjectBoardService boards;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMembers;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository workspaceMembers;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;

    private User pm, dev, viewer, outsider, admin;
    private Long workspaceId, projectId, otherProjectId, done;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        String suffix = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> {
            pm = user("pm", suffix);
            dev = user("dev", suffix);
            viewer = user("viewer", suffix);
            outsider = user("outsider", suffix);
            admin = user("admin", suffix);
            Workspace ws = workspaces.save(Workspace.builder().name("Milestone test").slug("milestone-" + suffix).build());
            workspaceId = ws.getId();
            for (User user : List.of(pm, dev, viewer, outsider, admin)) {
                workspaceMembers.save(WorkspaceMember.builder().workspace(ws).user(user)
                        .role(user.equals(admin) ? Role.WORKSPACE_ADMIN : Role.WORKSPACE_MEMBER).build());
            }
            projectId = projects.create(pm, workspaceId,
                    new CreateProjectRequest("Mobile", "MOB", ProjectTemplate.SCRUM, null)).id();
            otherProjectId = projects.create(pm, workspaceId,
                    new CreateProjectRequest("Web", "WEB", ProjectTemplate.KANBAN, null)).id();
            addMember(dev, ProjectRole.MEMBER);
            addMember(viewer, ProjectRole.VIEWER);
            done = boards.get(pm, projectId).columns().stream()
                    .filter(c -> c.category() == BoardColumnCategory.DONE).findFirst().orElseThrow().id();
        });
    }

    @AfterEach
    void cleanUp() {
        if (workspaceId == null) return;
        tx.executeWithoutResult(status -> {
            jdbc.update("delete from workspaces where id=?", workspaceId);
            for (User user : List.of(pm, dev, viewer, outsider, admin)) {
                jdbc.update("delete from email_outbox where recipient_email=?", user.getEmail());
                jdbc.update("delete from users where id=?", user.getId());
            }
        });
    }

    private User user(String name, String suffix) {
        return users.save(User.builder().firstName(name).lastName("Milestone test")
                .email(name + "-" + suffix + "@example.invalid").enabled(true).build());
    }

    private void addMember(User user, ProjectRole role) {
        projectMembers.save(ProjectMember.builder().project(projectRepository.getReferenceById(projectId))
                .user(user).roles(new HashSet<>(Set.of(role))).build());
    }

    private TaskResponse task(Long project, String title, Integer points) {
        return tasks.create(pm, project, new CreateTaskRequest(title, TaskType.TASK, null, null, points, null, null));
    }

    private List<Long> listed(TaskSearchFilters filters) {
        return tasks.list(pm, projectId, filters, TaskListOrder.NEWEST, PageRequest.of(0, 50)).content()
                .stream().map(TaskResponse::id).sorted().toList();
    }

    private static TaskSearchFilters milestoneFilter(Long milestoneId, Boolean noMilestone) {
        return new TaskSearchFilters(null, null, null, null, null, null, null, null, null, milestoneId, noMilestone);
    }

    private long milestoneHistory(Long taskId) {
        return jdbc.queryForObject("select count(*) from task_history where task_id=? and field='MILESTONE'",
                Long.class, taskId);
    }

    @Test
    void linkProgressDeleteAndRestoreOnPostgres() {
        MilestoneResponse beta = milestones.create(pm, projectId, new MilestoneRequest("Beta release", FUTURE, null));
        TaskResponse five = task(projectId, "Five", 5);
        TaskResponse three = task(projectId, "Three", 3);
        TaskResponse two = task(projectId, "Two", 2);
        TaskResponse loose = task(projectId, "Loose", null);
        subtasks.create(pm, projectId, five.id(), new CreateSubtaskRequest("Child", null, null, null));

        // a Developer links one task from the task page and two in bulk
        TaskResponse linked = milestones.setTaskMilestone(dev, five.id(), new TaskMilestoneRequest(beta.id()));
        assertEquals("Beta release", linked.milestone().name());
        assertEquals(FUTURE, linked.milestone().targetDate());
        milestones.linkTasks(dev, beta.id(), new MilestoneTasksRequest(List.of(three.id(), two.id(), five.id())));
        assertEquals(1, milestoneHistory(five.id()), "already linked: skipped");

        // criterion 15: the 5-point task is done
        tasks.update(pm, projectId, five.id(), new UpdateTaskRequest(null, null, null, done, null, null, null, null,
                false, false, false, true, true));
        MilestoneResponse read = milestones.get(viewer, beta.id());
        assertEquals(new MilestoneProgress(3, 1, 10, 5, MilestoneProgress.Basis.POINTS, 50), read.progress());
        assertEquals(MilestoneStatus.UPCOMING, read.status());
        assertFalse(read.canManage(), "a Viewer");
        assertEquals("pm Milestone test", read.createdBy().name());

        // the task list: filters, and the milestone on each row; a subtask shows its parent's
        assertEquals(List.of(five.id(), three.id(), two.id()).stream().sorted().toList(),
                listed(milestoneFilter(beta.id(), null)));
        assertEquals(List.of(loose.id()), listed(milestoneFilter(null, true)));
        assertEquals("Beta release", subtasks.list(pm, projectId, five.id()).get(0).milestone().name());

        // a deleted linked task, then the milestone delete unlinks everything
        tasks.delete(pm, projectId, two.id());
        milestones.delete(pm, beta.id());
        assertEquals(0L, jdbc.queryForObject("select count(*) from tasks where milestone_id=?", Long.class, beta.id()),
                "deleted tasks are unlinked too");
        assertNull(tasks.get(pm, projectId, five.id()).milestone());
        assertEquals(2, milestoneHistory(five.id()), "link and the delete's unlink");
        assertEquals(1, milestoneHistory(two.id()), "no entry for the deleted task");
        assertNull(tasks.restore(pm, projectId, two.id()).milestone(), "a restored task points at nothing");
        assertEquals("2", jdbc.queryForObject("""
                select c.new_value from audit_log_changes c join audit_log_entries e on e.id = c.entry_id
                where e.action = 'MILESTONE_DELETED' and e.target_id = ? and c.field = 'unlinkedTasks'""",
                String.class, beta.id()));
        assertTrue(milestones.list(pm, projectId).milestones().isEmpty());

        // restore brings it back empty
        MilestoneResponse restored = milestones.restore(pm, beta.id());
        assertEquals(MilestoneStatus.EMPTY, restored.status());
        assertEquals(0, restored.progress().totalTasks());
        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_log_entries where action='MILESTONE_RESTORED' "
                + "and target_id=?", Long.class, beta.id()));
    }

    @Test
    void rulesAndErrorsOverHttp() {
        String list = "/api/v1/projects/" + projectId + "/milestones";
        String body = "{\"name\":\"Beta release\",\"targetDate\":\"" + FUTURE + "\"}";
        HttpResult created = http(pm, HttpMethod.POST, list, body);
        assertEquals(201, created.status());
        long betaId = created.body().get("id").asLong();

        assertEquals(409, http(pm, HttpMethod.POST, list, body.replace("Beta", "BETA")).status());
        assertEquals("MILESTONE_NAME_TAKEN",
                http(pm, HttpMethod.POST, list, body.replace("Beta", "beta")).body().get("code").asString());
        assertEquals(403, http(dev, HttpMethod.POST, list, body.replace("Beta", "Gamma")).status());
        assertEquals(200, http(viewer, HttpMethod.GET, list, null).status());
        assertEquals(403, http(admin, HttpMethod.GET, list, null).status(), "workspace admin, not a member");
        assertEquals(404, http(outsider, HttpMethod.GET, list, null).status());
        assertEquals(404, http(outsider, HttpMethod.GET, "/api/v1/milestones/" + betaId, null).status());

        // linking rules
        TaskResponse parent = task(projectId, "Parent", null);
        TaskResponse child = subtasks.create(pm, projectId, parent.id(), new CreateSubtaskRequest("Child", null, null, null));
        TaskResponse elsewhere = task(otherProjectId, "Other project", null);
        String bulk = "/api/v1/milestones/" + betaId + "/tasks";
        assertEquals(400, http(dev, HttpMethod.POST, bulk, "{\"taskIds\":[" + parent.id() + "," + elsewhere.id() + "]}").status());
        assertNull(tasks.get(pm, projectId, parent.id()).milestone(), "all or nothing");
        assertEquals(400, http(dev, HttpMethod.POST, bulk, "{\"taskIds\":[]}").status());
        assertEquals(400, http(dev, HttpMethod.PUT, "/api/v1/tasks/" + child.id() + "/milestone",
                "{\"milestoneId\":" + betaId + "}").status());
        assertEquals(403, http(viewer, HttpMethod.PUT, "/api/v1/tasks/" + parent.id() + "/milestone",
                "{\"milestoneId\":" + betaId + "}").status());
        HttpResult set = http(dev, HttpMethod.PUT, "/api/v1/tasks/" + parent.id() + "/milestone",
                "{\"milestoneId\":" + betaId + "}");
        assertEquals(200, set.status());
        assertEquals("Beta release", set.body().get("milestone").get("name").asString());
        assertEquals(FUTURE.toString(), set.body().get("milestone").get("targetDate").asString());

        // task list JSON and filters
        String tasksPath = "/api/v1/projects/" + projectId + "/tasks";
        HttpResult filtered = http(pm, HttpMethod.GET, tasksPath + "?milestoneId=" + betaId, null);
        assertEquals(1, filtered.body().get("content").size());
        assertEquals(betaId, filtered.body().get("content").get(0).get("milestone").get("id").asLong());
        assertEquals(400, http(pm, HttpMethod.GET, tasksPath + "?milestoneId=" + betaId + "&noMilestone=true", null).status());

        // the 51st milestone
        jdbc.update("insert into milestones(project_id, name, target_date) "
                + "select ?, 'M' || n, date '2026-12-01' from generate_series(1, 49) n", projectId);
        HttpResult full = http(pm, HttpMethod.POST, list, body.replace("Beta", "Fifty-first"));
        assertEquals(409, full.status());
        assertEquals("MILESTONE_LIMIT_REACHED", full.body().get("code").asString());

        // a completed project is read-only, but still readable
        jdbc.update("update projects set lifecycle_status='COMPLETED' where id=?", projectId);
        HttpResult readOnly = http(pm, HttpMethod.DELETE, "/api/v1/milestones/" + betaId, null);
        assertEquals(409, readOnly.status());
        assertEquals("CONFLICT", readOnly.body().get("code").asString());
        HttpResult read = http(pm, HttpMethod.GET, list, null);
        assertEquals(200, read.status());
        assertFalse(read.body().get("canManage").asBoolean());
    }

    private record HttpResult(int status, JsonNode body) {}

    private HttpResult http(User user, HttpMethod method, String path, String body) {
        var request = RestClient.builder().requestFactory(new JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + port).build().method(method).uri(path)
                .header(HttpHeaders.COOKIE, AuthCookieFactory.ACCESS_COOKIE + "=" + jwt.generateAccessToken(user.getEmail()));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.exchange((req, res) -> {
            byte[] bytes = res.getBody().readAllBytes();
            return new HttpResult(res.getStatusCode().value(),
                    bytes.length == 0 ? null : JsonMapper.builder().build().readTree(bytes));
        });
    }
}
