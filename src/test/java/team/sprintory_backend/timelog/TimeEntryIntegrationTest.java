package team.sprintory_backend.timelog;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import team.sprintory_backend.security.auth.AuthCookieFactory;
import team.sprintory_backend.security.jwt.JwtService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import team.sprintory_backend.sprint.dto.SprintRequest;
import team.sprintory_backend.sprint.dto.MoveSprintTaskRequest;
import team.sprintory_backend.sprint.dto.CompleteSprintRequest;
import team.sprintory_backend.sprint.models.SprintGoalAchieved;
import team.sprintory_backend.sprint.services.SprintService;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import team.sprintory_backend.AbstractIntegrationTest;
import team.sprintory_backend.exceptionHandler.ErrorCode;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.dto.CreateProjectRequest;
import team.sprintory_backend.project.models.*;
import team.sprintory_backend.project.repositories.*;
import team.sprintory_backend.project.services.*;
import team.sprintory_backend.task.api.TaskHistoryOrder;
import team.sprintory_backend.task.dto.*;
import team.sprintory_backend.task.models.TaskHistoryType;
import team.sprintory_backend.task.services.*;
import team.sprintory_backend.timelog.dto.*;
import team.sprintory_backend.timelog.exceptions.TimeEntryException;
import team.sprintory_backend.timelog.exceptions.TimeEntryConflictException;
import team.sprintory_backend.timelog.services.TimeEntryService;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.models.*;
import team.sprintory_backend.workspace.repositories.*;
import team.sprintory_backend.workspace.exceptions.WorkspaceArchivedException;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent transactions against PostgreSQL; no mocked aggregate or lock. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TimeEntryIntegrationTest extends AbstractIntegrationTest {
    @Value("${local.server.port}") private int port;
    @Autowired private JwtService jwt;
    @Autowired private SprintService sprints;
    @Autowired private TimeEntryService time;
    @Autowired private TaskApiService tasks;
    @Autowired private SubtaskService subtasks;
    @Autowired private ProjectService projects;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMembers;
    @Autowired private ProjectBoardService boards;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository workspaceMembers;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private User pm, dev, viewer, outsider, admin;
    private Long workspaceId, projectId, foreignProjectId;
    private Long done;
    private String suffix;

    @BeforeEach void setup() {
        tx = new TransactionTemplate(txManager);
        suffix = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> {
            pm = user("pm"); dev = user("dev"); viewer = user("viewer"); outsider = user("outsider"); admin = user("admin");
            Workspace ws = workspaces.save(Workspace.builder().name("Time logging test").slug("time-" + suffix).build());
            workspaceId = ws.getId();
            for (User user : List.of(pm,dev,viewer,outsider,admin)) {
                workspaceMembers.save(WorkspaceMember.builder().workspace(ws).user(user)
                    .role(user.equals(admin) ? Role.WORKSPACE_ADMIN : Role.WORKSPACE_MEMBER).build());
            }
            projectId = projects.create(pm,workspaceId,new CreateProjectRequest("Time logging","TIME",ProjectTemplate.SCRUM,null)).id();
            foreignProjectId = projects.create(pm,workspaceId,new CreateProjectRequest("Other","OTHER",ProjectTemplate.KANBAN,null)).id();
            Project project = projectRepository.getReferenceById(projectId);
            projectMembers.save(ProjectMember.builder().project(project).user(dev)
                .roles(new HashSet<>(Set.of(ProjectRole.MEMBER))).build());
            projectMembers.save(ProjectMember.builder().project(project).user(viewer)
                .roles(new HashSet<>(Set.of(ProjectRole.VIEWER))).build());
            projectMembers.save(ProjectMember.builder().project(projectRepository.getReferenceById(foreignProjectId)).user(dev)
                .roles(new HashSet<>(Set.of(ProjectRole.MEMBER))).build());
            var columns = boards.get(pm,projectId).columns();
            done = columns.stream().filter(c -> c.category()==BoardColumnCategory.DONE).findFirst().orElseThrow().id();
        });
    }
    @AfterEach void cleanup() {
        if (workspaceId==null) return;
        tx.executeWithoutResult(status -> {
            jdbc.update("delete from workspaces where id=?", workspaceId);
            for (User user : List.of(pm,dev,viewer,outsider,admin)) {
                jdbc.update("delete from email_outbox where recipient_email=?",user.getEmail());
                jdbc.update("delete from users where id=?",user.getId());
            }
        });
    }
    private User user(String name) {
        return users.save(User.builder().firstName(name).lastName("Time logging test")
            .email(name+"-"+suffix+"@example.invalid").enabled(true).build());
    }
    private TaskResponse create(String title) { return create(pm,projectId,title,null); }
    private TaskResponse create(User user, Long pid, String title, Long assignee) {
        return tasks.create(user,pid,new CreateTaskRequest(title,null,null,assignee,null,null,null));
    }

    private LocalDate date() { return LocalDate.now().minusDays(2); }
    private TimeEntryRequest request(int minutes) { return new TimeEntryRequest(date(), minutes, null); }

    @Test void createEditDeleteHistoryAndPrivacy() {
        var task = create("Log work");
        var entry = time.create(dev, task.id(), new TimeEntryRequest(null, 90, "<script>private</script>"));
        assertEquals(LocalDate.now(), entry.workDate());
        assertEquals("<script>private</script>", entry.note());
        assertEquals(90, time.summary(viewer, task.id()).totalMinutes());
        assertEquals(1, time.mine(dev, task.id(), true).size());
        assertTrue(time.mine(pm, task.id(), true).isEmpty());
        assertFalse(time.summary(viewer, task.id()).canLog());
        assertThrows(TimeEntryException.class, () -> time.mine(pm, task.id(), false));
        var changed = time.update(dev, entry.id(), new TimeEntryRequest(date(), 120, "new private note"));
        assertEquals(120, changed.minutes());
        time.update(dev, entry.id(), new TimeEntryRequest(date(), 120, "new private note")); // no-op
        time.delete(dev, entry.id());
        assertEquals(0, time.summary(pm, task.id()).totalMinutes());
        assertTrue(time.mine(dev, task.id(), true).isEmpty());
        assertNotNull(jdbc.queryForObject("select deleted_at from time_entry where id=?", java.sql.Timestamp.class, entry.id()));
        var history = tasks.history(pm, projectId, task.id(), TaskHistoryOrder.OLDEST, PageRequest.of(0, 100)).content()
                .stream().filter(h -> h.type().name().startsWith("TIME_ENTRY_")).toList();
        assertEquals(List.of(TaskHistoryType.TIME_ENTRY_ADDED, TaskHistoryType.TIME_ENTRY_CHANGED, TaskHistoryType.TIME_ENTRY_DELETED),
                history.stream().map(TaskHistoryEntryResponse::type).toList());
        assertTrue(history.get(1).newValue().contains("note changed"));
        assertFalse(history.toString().contains("private"));
        assertEquals(entry.id(), history.get(0).refId());
    }

    @Test void workspaceCapAndEditingExcludesOwnEntry() {
        var a = create("A"); var b = create(pm, foreignProjectId, "B", null);
        var first = time.create(dev, a.id(), request(1200));
        assertEquals(ErrorCode.TIME_DAILY_LIMIT_EXCEEDED, assertThrows(TimeEntryConflictException.class,
                () -> time.create(dev, b.id(), request(300))).getCode());
        var second = time.create(dev, b.id(), request(240));
        time.update(dev, second.id(), request(240));
        assertThrows(TimeEntryConflictException.class, () -> time.update(dev, second.id(), request(241)));
        time.update(dev, second.id(), new TimeEntryRequest(date().minusDays(1), 300, null));
        time.update(dev, first.id(), request(1440));
        time.delete(dev, first.id());
        assertEquals(1440, time.create(dev, a.id(), request(1440)).minutes());
        assertEquals(1440, time.create(pm, a.id(), request(1440)).minutes()); // different author
    }

    @Test void totalsChildrenDeletionRestorationAndRetainedDailyCap() {
        var parent = create("Parent");
        var child = subtasks.create(pm, projectId, parent.id(), new CreateSubtaskRequest("Child", null, null, null));
        time.create(dev, parent.id(), request(120));
        var childEntry = time.create(dev, child.id(), request(90));
        var total = time.summary(viewer, parent.id());
        assertEquals(120, total.ownMinutes()); assertEquals(90, total.subtasksMinutes()); assertEquals(210, total.totalMinutes());
        assertEquals(90, time.summary(pm, child.id()).ownMinutes());
        assertEquals(1, time.mine(dev, parent.id(), true).size());
        tasks.delete(pm, projectId, child.id());
        assertEquals(120, time.summary(pm, parent.id()).totalMinutes());
        assertThrows(ResourceNotFoundException.class, () -> time.create(dev, child.id(), request(1)));
        assertThrows(ResourceNotFoundException.class, () -> time.delete(dev, childEntry.id()));
        assertThrows(TimeEntryConflictException.class, () -> time.create(dev, parent.id(), request(1231)));
        tasks.restore(pm, projectId, child.id());
        assertEquals(210, time.summary(pm, parent.id()).totalMinutes());
        assertEquals(childEntry.id(), time.mine(dev, child.id(), true).get(0).id());
    }

    @Test void roleOwnershipAndClosedProjects() {
        var task = create("Protected"); var entry = time.create(dev, task.id(), request(60));
        assertThrows(AccessDeniedException.class, () -> time.update(pm, entry.id(), request(1)));
        assertThrows(AccessDeniedException.class, () -> time.delete(pm, entry.id()));
        assertThrows(AccessDeniedException.class, () -> time.create(viewer, task.id(), request(1)));
        assertThrows(AccessDeniedException.class, () -> time.update(viewer, entry.id(), request(1)));
        assertThrows(AccessDeniedException.class, () -> time.delete(viewer, entry.id()));
        for (User stranger : List.of(outsider, admin)) {
            assertThrows(ResourceNotFoundException.class, () -> time.summary(stranger, task.id()));
            assertThrows(ResourceNotFoundException.class, () -> time.mine(stranger, task.id(), true));
            assertThrows(ResourceNotFoundException.class, () -> time.create(stranger, task.id(), request(1)));
            assertThrows(ResourceNotFoundException.class, () -> time.update(stranger, entry.id(), request(1)));
            assertThrows(ResourceNotFoundException.class, () -> time.delete(stranger, entry.id()));
        }
        for (String state : List.of("COMPLETED", "ARCHIVED")) {
            jdbc.update("update projects set lifecycle_status=? where id=?", state, projectId);
            assertEquals(60, time.summary(viewer, task.id()).totalMinutes());
            assertFalse(time.summary(dev, task.id()).canLog());
            assertThrows(TimeEntryConflictException.class, () -> time.create(dev, task.id(), request(1)));
            assertThrows(TimeEntryConflictException.class, () -> time.update(dev, entry.id(), request(1)));
            assertThrows(TimeEntryConflictException.class, () -> time.delete(dev, entry.id()));
        }
        jdbc.update("update projects set lifecycle_status='ACTIVE' where id=?", projectId);
        jdbc.update("update workspaces set archived_at=now() where id=?", workspaceId);
        assertFalse(time.summary(dev, task.id()).canLog());
        assertThrows(WorkspaceArchivedException.class, () -> time.delete(dev, entry.id()));
    }

    @Test void formerMembersAndReassignmentKeepAttribution() {
        var task = create(dev, projectId, "Assigned", dev.getId());
        var entry = time.create(dev, task.id(), request(30));
        jdbc.update("update tasks set assignee_id=? where id=?", pm.getId(), task.id());
        assertEquals(entry.id(), time.mine(dev, task.id(), true).get(0).id());
        tx.executeWithoutResult(status -> projectMembers.deleteByProjectIdAndUserId(projectId, dev.getId()));
        assertEquals(30, time.summary(pm, task.id()).totalMinutes());
        assertThrows(ResourceNotFoundException.class, () -> time.mine(dev, task.id(), true));
    }

    @Test void concurrentCrossProjectLogsCannotExceedCap() throws Exception {
        var a = create("A"); var b = create(pm, foreignProjectId, "B", null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            Future<Boolean> f1 = pool.submit(() -> race(a.id(), ready, start));
            Future<Boolean> f2 = pool.submit(() -> race(b.id(), ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            assertNotEquals(f1.get(20, TimeUnit.SECONDS), f2.get(20, TimeUnit.SECONDS));
            assertEquals(800, time.summary(pm, a.id()).totalMinutes() + time.summary(pm, b.id()).totalMinutes());
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    private boolean race(Long taskId, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown(); start.await();
        try { time.create(dev, taskId, request(800)); return true; }
        catch (TimeEntryConflictException e) { assertEquals(ErrorCode.TIME_DAILY_LIMIT_EXCEEDED, e.getCode()); return false; }
    }

    @Test void validationServerDateGraceAndDoneTask() {
        var task = create("Done task");
        jdbc.update("update tasks set status_column_id=? where id=?", done, task.id());
        assertEquals(1, time.create(dev, task.id(), new TimeEntryRequest(LocalDate.now().plusDays(1), 1, null)).minutes());
        assertThrows(TimeEntryException.class, () -> time.create(dev, task.id(), new TimeEntryRequest(LocalDate.now().plusDays(2), 1, null)));
        for (int minutes : List.of(0, -1, 1441)) assertThrows(TimeEntryException.class, () -> time.create(dev, task.id(), request(minutes)));
        assertThrows(TimeEntryException.class, () -> time.create(dev, task.id(), new TimeEntryRequest(date(), 1, "a".repeat(501))));
    }

    @Test void httpContractAndStrictIntegerMinutes() {
        var task = create("HTTP");
        String path = "/api/v1/tasks/" + task.id();
        var created = http(dev, HttpMethod.POST, path + "/time-entries", "{\"minutes\":90,\"note\":\"plain\"}");
        assertEquals(201, created.status());
        long id = created.body().get("id").asLong();
        assertEquals(90, created.body().get("minutes").asLong());
        String edit = "/api/v1/time-entries/" + id;
        assertEquals(403, http(pm, HttpMethod.DELETE, edit, null).status());
        assertEquals(404, http(admin, HttpMethod.GET, path + "/time-summary", null).status());
        assertEquals(403, http(viewer, HttpMethod.POST, path + "/time-entries", "{\"minutes\":1}").status());
        for (String invalid : List.of("1.5", "\"90\""))
            assertError(400, "BAD_REQUEST", http(dev, HttpMethod.POST, path + "/time-entries", "{\"minutes\":" + invalid + "}"));
        for (String invalid : List.of("0", "1441", "null"))
            assertError(400, "INVALID_TIME_ENTRY", http(dev, HttpMethod.POST, path + "/time-entries", "{\"minutes\":" + invalid + "}"));
        assertError(400, "INVALID_TIME_ENTRY", http(dev, HttpMethod.POST, path + "/time-entries", "{}"));
        assertError(400, "INVALID_TIME_ENTRY", http(dev, HttpMethod.POST, path + "/time-entries",
                "{\"minutes\":1,\"note\":\"" + "x".repeat(501) + "\"}"));
        assertError(400, "INVALID_TIME_ENTRY", http(dev, HttpMethod.PUT, edit, "{\"minutes\":1}"));
        assertError(400, "INVALID_TIME_ENTRY", http(dev, HttpMethod.POST, path + "/time-entries",
                "{\"minutes\":1,\"workDate\":\"" + LocalDate.now().plusDays(2) + "\"}"));
        assertError(409, "TIME_DAILY_LIMIT_EXCEEDED", http(dev, HttpMethod.POST, path + "/time-entries", "{\"minutes\":1351}"));
        assertTrue(created.body().get("createdAt").isString());
        assertTrue(created.body().get("updatedAt").isString());
        assertEquals(dev.getEmail(), jdbc.queryForObject("select created_by from time_entry where id=?", String.class, id));
        assertEquals(dev.getEmail(), jdbc.queryForObject("select updated_by from time_entry where id=?", String.class, id));
        assertEquals(0, http(pm, HttpMethod.GET, path + "/time-entries?mine=true", null).body().size());
        assertEquals(90, http(viewer, HttpMethod.GET, path + "/time-summary", null).body().get("totalMinutes").asLong());
        assertEquals(200, http(dev, HttpMethod.PUT, edit, "{\"minutes\":120,\"workDate\":\"" + date() + "\"}").status());
        var other = http(dev, HttpMethod.POST, path + "/time-entries",
                "{\"minutes\":1320,\"workDate\":\"" + date() + "\"}");
        assertEquals(201, other.status());
        assertError(409, "TIME_DAILY_LIMIT_EXCEEDED", http(dev, HttpMethod.PUT, edit,
                "{\"minutes\":121,\"workDate\":\"" + date() + "\"}"));
        assertEquals(204, http(dev, HttpMethod.DELETE, "/api/v1/time-entries/" + other.body().get("id").asLong(), null).status());
        assertEquals(204, http(dev, HttpMethod.DELETE, edit, null).status());
        assertEquals(0, http(dev, HttpMethod.GET, path + "/time-entries", null).body().size());
    }

    @Test void lateLoggingAfterSprintCompletes() {
        tx.executeWithoutResult(status -> projectMembers.findWithRolesByProjectIdAndUserId(projectId, dev.getId())
                .orElseThrow().getRoles().add(ProjectRole.SCRUM_MASTER));
        var task = create("Finished");
        var sprint = sprints.create(dev, projectId, new SprintRequest("Time sprint", "Late logs", null, null, null));
        sprints.move(dev, projectId, new MoveSprintTaskRequest(task.id(), sprint.id(), null));
        sprints.start(dev, sprint.id(), null);
        jdbc.update("update tasks set status_column_id=? where id=?", done, task.id());
        sprints.complete(dev, sprint.id(), new CompleteSprintRequest(null, false, SprintGoalAchieved.YES, null, true));
        assertEquals(sprint.id(), tasks.get(pm, projectId, task.id()).sprintId());
        assertEquals(45, time.create(dev, task.id(), request(45)).minutes());
    }

    private static void assertError(int status, String code, HttpResult result) {
        assertEquals(status, result.status());
        assertEquals(code, result.body().get("code").asText());
    }

    private record HttpResult(int status, JsonNode body) { }
    private HttpResult http(User user, HttpMethod method, String path, String body) {
        var request = RestClient.builder().requestFactory(new JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + port).build().method(method).uri(path)
                .header(HttpHeaders.COOKIE, AuthCookieFactory.ACCESS_COOKIE + "=" + jwt.generateAccessToken(user.getEmail()));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.exchange((req, res) -> {
            byte[] bytes = res.getBody().readAllBytes();
            return new HttpResult(res.getStatusCode().value(), bytes.length == 0 ? null : JsonMapper.builder().build().readTree(bytes));
        });
    }
}
