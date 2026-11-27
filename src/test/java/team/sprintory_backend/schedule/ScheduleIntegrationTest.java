package team.sprintory_backend.schedule;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import team.sprintory_backend.AbstractIntegrationTest;
import team.sprintory_backend.dependency.dto.DependencyCreateRequest;
import team.sprintory_backend.dependency.services.DependencyService;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.milestone.dto.MilestoneRequest;
import team.sprintory_backend.milestone.dto.MilestoneTasksRequest;
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
import team.sprintory_backend.schedule.api.ScheduleFilters;
import team.sprintory_backend.schedule.dto.ScheduleConflictType;
import team.sprintory_backend.schedule.dto.ScheduleMilestone;
import team.sprintory_backend.schedule.dto.ScheduleTask;
import team.sprintory_backend.schedule.services.ScheduleService;
import team.sprintory_backend.security.auth.AuthCookieFactory;
import team.sprintory_backend.security.jwt.JwtService;
import team.sprintory_backend.sprint.dto.CompleteSprintRequest;
import team.sprintory_backend.sprint.dto.MoveSprintTaskRequest;
import team.sprintory_backend.sprint.dto.SprintRequest;
import team.sprintory_backend.sprint.models.SprintGoalAchieved;
import team.sprintory_backend.sprint.services.SprintService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import team.sprintory_backend.task.api.TaskHistoryOrder;
import team.sprintory_backend.task.dto.CreateSubtaskRequest;
import team.sprintory_backend.task.dto.CreateTaskRequest;
import team.sprintory_backend.task.dto.TaskResponse;
import team.sprintory_backend.task.dto.UpdateTaskRequest;
import team.sprintory_backend.task.exceptions.TaskDatesReadOnlyException;
import team.sprintory_backend.task.models.TaskHistoryField;
import team.sprintory_backend.task.models.TaskType;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.task.services.SubtaskService;
import team.sprintory_backend.task.services.TaskApiService;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;
import team.sprintory_backend.workspace.repositories.WorkspaceRepository;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the actual Flyway schema, projection queries, permissions and task history on PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScheduleIntegrationTest extends AbstractIntegrationTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    private static final ScheduleFilters ALL = new ScheduleFilters(null, null, null, null, null, null, false, null, null);
    @Value("${local.server.port}") private int port;
    @Autowired private JwtService jwt;
    @Autowired private SprintService sprints;
    @Autowired private ScheduleService schedule;
    @Autowired private MilestoneService milestones;
    @Autowired private TaskApiService tasks;
    @Autowired private TaskRepository taskRepository;
    @Autowired private SubtaskService subtasks;
    @Autowired private DependencyService deps;
    @Autowired private ProjectService projects;
    @Autowired private ProjectBoardService boards;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMembers;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository workspaceMembers;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;
    private User pm, viewer, outsider, admin;
    private Long workspaceId, projectId, kanbanId, todo, done;
    private TransactionTemplate tx;

    @BeforeEach void setup() {
        tx = new TransactionTemplate(txManager);
        String suffix = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> {
            pm = user("pm", suffix); viewer = user("viewer", suffix);
            outsider = user("outsider", suffix); admin = user("admin", suffix);
            Workspace ws = workspaces.save(Workspace.builder().name("Schedule test").slug("schedule-" + suffix).build());
            workspaceId = ws.getId();
            for (User user : List.of(pm, viewer, outsider, admin)) {
                workspaceMembers.save(WorkspaceMember.builder().workspace(ws).user(user)
                        .role(user.equals(admin) ? Role.WORKSPACE_ADMIN : Role.WORKSPACE_MEMBER).build());
            }
            projectId = projects.create(pm, workspaceId, new CreateProjectRequest("Timeline", "TIME", ProjectTemplate.SCRUM, null)).id();
            kanbanId = projects.create(pm, workspaceId, new CreateProjectRequest("Kanban", "KAN", ProjectTemplate.KANBAN, null)).id();
            projectMembers.save(ProjectMember.builder().project(projectRepository.getReferenceById(projectId))
                    .user(viewer).roles(new HashSet<>(Set.of(ProjectRole.VIEWER))).build());
            var columns = boards.get(pm, projectId).columns();
            todo = columns.stream().filter(c -> c.category() == BoardColumnCategory.TODO).findFirst().orElseThrow().id();
            done = columns.stream().filter(c -> c.category() == BoardColumnCategory.DONE).findFirst().orElseThrow().id();
        });
    }

    @AfterEach void cleanup() {
        if (workspaceId == null) return;
        tx.executeWithoutResult(status -> {
            jdbc.update("delete from workspaces where id=?", workspaceId);
            for (User user : List.of(pm, viewer, outsider, admin)) {
                jdbc.update("delete from email_outbox where recipient_email=?", user.getEmail());
                jdbc.update("delete from users where id=?", user.getId());
            }
        });
    }

    private User user(String name, String suffix) {
        return users.save(User.builder().firstName(name).lastName("Schedule test")
                .email(name + "-" + suffix + "@example.invalid").enabled(true).build());
    }

    private TaskResponse create(Long pid, String title, LocalDate start, LocalDate due, Long assignee) {
        return tasks.create(pm, pid, new CreateTaskRequest(title, TaskType.TASK, null, assignee, null, null, null, start, due));
    }

    private UpdateTaskRequest dates(LocalDate start, LocalDate due, boolean clear) {
        return new UpdateTaskRequest(null, null, null, null, null, null, null, null,
                false, false, false, false, false, start, due, clear);
    }

    @Test void datesRoundTripHistoryNoOpClearAndDatabaseConstraint() {
        var row = create(projectId, "Task", null, null, null);
        var changed = tasks.update(pm, projectId, row.id(), dates(DAY, DAY, false));
        assertEquals(DAY, changed.startDate());
        assertEquals(DAY, tasks.get(pm, projectId, row.id()).dueDate());
        tasks.update(pm, projectId, row.id(), dates(DAY, DAY, false));
        tasks.update(pm, projectId, row.id(), new UpdateTaskRequest("Renamed", null, null, null, null, null, null, null, false, false, false));
        assertEquals(DAY, tasks.get(pm, projectId, row.id()).startDate());
        tasks.update(pm, projectId, row.id(), dates(null, null, true));
        assertNull(tasks.get(pm, projectId, row.id()).startDate());
        var history = tasks.history(pm, projectId, row.id(), TaskHistoryOrder.OLDEST, PageRequest.of(0, 100)).content()
                .stream().filter(h -> h.field() == TaskHistoryField.START_DATE || h.field() == TaskHistoryField.DUE_DATE).toList();
        assertEquals(4, history.size());
        assertEquals(DAY.toString(), history.get(0).newValue());
        assertNull(history.get(0).oldValue());
        assertEquals(DAY.toString(), history.get(2).oldValue());
        assertNull(history.get(2).newValue());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("update tasks set start_date=? where id=?", DAY, row.id()));
    }

    @Test void unrelatedEditCannotOverwriteDatesFromAnotherTransaction() {
        var row = create(projectId, "Original", null, null, null);
        TransactionTemplate other = new TransactionTemplate(txManager);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            // The first persistence context holds an old snapshot of the task.
            assertNull(taskRepository.findByIdAndProjectId(row.id(), projectId).orElseThrow().getStartDate());
            other.executeWithoutResult(inner -> tasks.update(pm, projectId, row.id(), dates(DAY, DAY, false)));
            tasks.update(pm, projectId, row.id(), new UpdateTaskRequest("New title", null, null, null,
                    null, null, null, null, false, false, false));
        });
        var saved = tasks.get(pm, projectId, row.id());
        assertEquals("New title", saved.title());
        assertEquals(DAY, saved.startDate());
        assertEquals(DAY, saved.dueDate());
    }

    @Test void rejectsBadDatesAndReadOnlyEditsButAllowsReads() {
        var row = create(projectId, "Task", DAY, DAY, null);
        assertThrows(BadRequestException.class, () -> create(projectId, "Bad", DAY, null, null));
        assertThrows(BadRequestException.class, () -> tasks.update(pm, projectId, row.id(), dates(DAY, null, false)));
        assertThrows(BadRequestException.class, () -> tasks.update(pm, projectId, row.id(), dates(DAY, DAY.minusDays(1), false)));
        assertThrows(BadRequestException.class, () -> tasks.update(pm, projectId, row.id(), dates(DAY, DAY, true)));
        assertThrows(AccessDeniedException.class, () -> tasks.update(viewer, projectId, row.id(), dates(null, null, true)));
        assertFalse(schedule.get(viewer, projectId, ALL).canEditDates());
        for (User nonmember : List.of(outsider, admin))
            assertThrows(ResourceNotFoundException.class, () -> schedule.get(nonmember, projectId, ALL));
        for (String state : List.of("COMPLETED", "ARCHIVED")) {
            jdbc.update("update projects set lifecycle_status=? where id=?", state, projectId);
            assertThrows(TaskDatesReadOnlyException.class, () -> tasks.update(pm, projectId, row.id(), dates(null, null, true)));
            assertThrows(TaskDatesReadOnlyException.class, () -> create(projectId, "New", DAY, DAY, null));
            assertFalse(schedule.get(pm, projectId, ALL).canEditDates());
            assertEquals(1, schedule.get(viewer, projectId, ALL).tasks().size());
        }
    }

    @Test void rowsArrowsHintsFiltersAndConflictsUseLiveProjectTasks() {
        var blocked = create(projectId, "Blocked", DAY, DAY.plusDays(2), viewer.getId());
        var blocker = create(projectId, "Blocker", DAY.minusDays(2), DAY, pm.getId());
        var unscheduled = create(projectId, "Unscheduled", null, null, null);
        var deleted = create(projectId, "Deleted", DAY, DAY, null);
        subtasks.create(pm, projectId, blocked.id(), new CreateSubtaskRequest("Child", null, null, null));
        deps.add(pm, blocked.id(), new DependencyCreateRequest(blocker.id(), blocked.id()));
        deps.add(pm, unscheduled.id(), new DependencyCreateRequest(blocker.id(), unscheduled.id()));
        deps.add(pm, deleted.id(), new DependencyCreateRequest(deleted.id(), blocked.id()));
        tasks.delete(pm, projectId, deleted.id());
        create(kanbanId, "Other project", DAY, DAY, null);
        var result = schedule.get(viewer, projectId, ALL);
        assertEquals(List.of(blocker.id(), blocked.id()), result.tasks().stream().map(ScheduleTask::id).toList());
        assertEquals(1, result.dependencies().size());
        assertEquals(1, result.conflicts().size());
        assertEquals(1, result.unscheduled().size());
        assertEquals(List.of(blocker.key()), result.unscheduled().get(0).blockerKeys());
        assertEquals(DAY.minusDays(2), result.earliestStartDate());
        assertEquals(DAY.plusDays(2), result.latestDueDate());
        assertEquals(LocalDate.now(), result.today());
        assertEquals(1, schedule.get(pm, kanbanId, ALL).tasks().size());
        var filtered = schedule.get(pm, projectId, new ScheduleFilters(viewer.getId(), null, null, null, null, null, false, DAY, DAY));
        assertEquals(List.of(blocked.id()), filtered.tasks().stream().map(ScheduleTask::id).toList());
        assertTrue(filtered.dependencies().isEmpty());
        assertEquals(1, filtered.conflicts().size());
        tasks.update(pm, projectId, blocked.id(), dates(DAY.plusDays(1), DAY.plusDays(2), false));
        assertTrue(schedule.get(pm, projectId, ALL).conflicts().isEmpty());
        tasks.update(pm, projectId, blocked.id(), dates(DAY, DAY, false));
        tasks.update(pm, projectId, blocker.id(), new UpdateTaskRequest(null, null, null, done, null, null, null, null, false, false, false, false, true));
        assertTrue(schedule.get(pm, projectId, ALL).conflicts().isEmpty());
        assertEquals(1, schedule.get(pm, projectId, new ScheduleFilters(null, null, null, null, null, null, true, null, null)).tasks().size());
    }

    /** PMP-46: diamonds, the milestone filter and "ends after milestone" conflicts on real milestones. */
    @Test void milestonesAreDiamondsFilterRowsAndRaiseConflicts() {
        var late = create(projectId, "Late", DAY, DAY.plusDays(3), null);
        var onTime = create(projectId, "On time", DAY, DAY.plusDays(1), null);
        var other = create(projectId, "Other", DAY, DAY, null);
        var beta = milestones.create(pm, projectId, new MilestoneRequest("Beta release", DAY.plusDays(1), null));
        milestones.create(pm, projectId, new MilestoneRequest("Launch", DAY.plusDays(9), null));
        milestones.linkTasks(pm, beta.id(), new MilestoneTasksRequest(List.of(late.id(), onTime.id())));

        var result = schedule.get(viewer, projectId, ALL);
        assertTrue(result.milestonesAvailable());
        assertEquals(List.of("Beta release", "Launch"), result.milestones().stream().map(ScheduleMilestone::name).toList());
        assertEquals(beta.id(), row(result.tasks(), late.id()).milestoneId());
        assertNull(row(result.tasks(), other.id()).milestoneId());
        var conflicts = result.conflicts().stream().filter(c -> c.type() == ScheduleConflictType.MILESTONE).toList();
        assertEquals(1, conflicts.size());
        assertEquals(List.of(late.id()), conflicts.get(0).taskIds());
        assertEquals(late.key() + " ends after milestone Beta release", conflicts.get(0).message());

        var filtered = schedule.get(pm, projectId, new ScheduleFilters(null, null, null, beta.id(), null, null, false, null, null));
        assertEquals(Set.of(late.id(), onTime.id()),
                Set.copyOf(filtered.tasks().stream().map(ScheduleTask::id).toList()));
        assertEquals(2, filtered.milestones().size(), "diamonds stay project-wide");

        milestones.delete(pm, beta.id());
        var after = schedule.get(pm, projectId, ALL);
        assertEquals(List.of("Launch"), after.milestones().stream().map(ScheduleMilestone::name).toList());
        assertTrue(after.tasks().stream().allMatch(row -> row.milestoneId() == null));
        assertTrue(after.conflicts().isEmpty());
    }

    private static ScheduleTask row(List<ScheduleTask> rows, Long id) {
        return rows.stream().filter(row -> row.id().equals(id)).findFirst().orElseThrow();
    }

    @Test void datesSurviveSprintCarryOverAndSprintFilter() {
        tx.executeWithoutResult(status -> projectMembers.findWithRolesByProjectIdAndUserId(projectId, pm.getId())
                .orElseThrow().getRoles().add(ProjectRole.SCRUM_MASTER));
        var task = create(projectId, "Carry over", DAY, DAY.plusDays(5), null);
        var sprint = sprints.create(pm, projectId, new SprintRequest("Sprint", "Test carry over", null, null, null));
        sprints.move(pm, projectId, new MoveSprintTaskRequest(task.id(), sprint.id(), null));
        sprints.start(pm, sprint.id(), null);
        var filter = new ScheduleFilters(null, null, null, null, sprint.id(), null, false, null, null);
        assertEquals(1, schedule.get(pm, projectId, filter).tasks().size());
        sprints.complete(pm, sprint.id(), new CompleteSprintRequest(null, false, SprintGoalAchieved.NO, null, true));
        var row = tasks.get(pm, projectId, task.id());
        assertNull(row.sprintId());
        assertEquals(DAY, row.startDate());
        assertEquals(DAY.plusDays(5), row.dueDate());
        assertTrue(schedule.get(pm, projectId, filter).tasks().isEmpty());
    }

    private record HttpResult(int status, JsonNode body) {}

    private HttpResult http(User user, HttpMethod method, String path, String body) {
        var request = RestClient.builder().requestFactory(new JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + port).build().method(method).uri(path)
                .header(HttpHeaders.COOKIE, AuthCookieFactory.ACCESS_COOKIE + "=" + jwt.generateAccessToken(user.getEmail()));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.exchange((req, res) -> new HttpResult(res.getStatusCode().value(),
                JsonMapper.builder().build().readTree(res.getBody().readAllBytes())));
    }

    @Test void realHttpDateValidationAndMembership() {
        var row = create(projectId, "HTTP task", null, null, null);
        String projectPath = "/api/v1/projects/" + projectId;
        String taskPath = projectPath + "/tasks/" + row.id();
        String schedulePath = projectPath + "/schedule";
        assertEquals(400, http(pm, HttpMethod.PATCH, taskPath, "{\"startDate\":\"2026-11-10\"}").status());
        String both = "{\"startDate\":\"2026-11-10\",\"dueDate\":\"2026-11-10\"}";
        assertEquals(403, http(viewer, HttpMethod.PATCH, taskPath, both).status());
        var saved = http(pm, HttpMethod.PATCH, taskPath, both);
        assertEquals(200, saved.status());
        assertEquals("2026-11-10", saved.body().get("startDate").asString());
        var chart = http(viewer, HttpMethod.GET, schedulePath + "?from=2026-11-10&to=2026-11-10", null);
        assertEquals(200, chart.status());
        assertEquals(1, chart.body().get("tasks").size());
        assertFalse(chart.body().get("canEditDates").asBoolean());
        assertFalse(chart.body().get("tasks").get(0).has("description"));
        assertEquals(404, http(admin, HttpMethod.GET, schedulePath, null).status());
        assertEquals(404, http(outsider, HttpMethod.GET, schedulePath, null).status());
        assertEquals(400, http(pm, HttpMethod.GET, schedulePath + "?from=2026-11-11&to=2026-11-10", null).status());
        assertEquals(400, http(pm, HttpMethod.GET, schedulePath + "?milestoneId=0", null).status());
        var noSuchMilestone = http(pm, HttpMethod.GET, schedulePath + "?milestoneId=999999", null);
        assertEquals(200, noSuchMilestone.status());
        assertTrue(noSuchMilestone.body().get("milestonesAvailable").asBoolean());
        assertEquals(0, noSuchMilestone.body().get("tasks").size());
        jdbc.update("update projects set lifecycle_status='ARCHIVED' where id=?", projectId);
        assertEquals(409, http(pm, HttpMethod.PATCH, taskPath, "{\"clearDates\":true}").status());
    }

    @Test void fiveHundredRowsAndDependenciesInOneSchedule() {
        jdbc.update("""
            insert into tasks(project_id,task_number,task_key,title,type,priority,status_column_id,board_position,backlog_rank,start_date,due_date)
            select ?, n, 'TIME-' || n, 'Task ' || n, 'TASK', 'MEDIUM', ?, n, n,
                   date '2026-11-01' + (n % 30), date '2026-11-01' + (n % 30) + 2
            from generate_series(1,500) n
            """, projectId, todo);
        jdbc.update("""
            insert into task_dependency(project_id,blocker_task_id,blocked_task_id,created_by,created_at)
            select a.project_id,a.id,b.id,?,now() from tasks a join tasks b
            on a.project_id=b.project_id and b.task_number=a.task_number+1 where a.project_id=?
            """, pm.getId(), projectId);
        long before = System.nanoTime();
        var response = schedule.get(pm, projectId, ALL);
        long elapsedMs = (System.nanoTime() - before) / 1_000_000;
        assertEquals(500, response.tasks().size());
        assertEquals(499, response.dependencies().size());
        assertTrue(response.unscheduled().isEmpty());
        System.out.println("Schedule 500 rows / 499 edges backend read: " + elapsedMs + " ms");
        String uri = "/api/v1/projects/" + projectId + "/schedule";
        before = System.nanoTime();
        var httpResponse = http(pm, HttpMethod.GET, uri, null);
        long httpMs = (System.nanoTime() - before) / 1_000_000;
        assertEquals(200, httpResponse.status());
        assertEquals(500, httpResponse.body().get("tasks").size());
        System.out.println("Schedule 500 rows / 499 edges HTTP read and JSON parse: " + httpMs + " ms");
    }
}
