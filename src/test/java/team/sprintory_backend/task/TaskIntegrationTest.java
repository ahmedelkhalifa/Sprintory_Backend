package team.sprintory_backend.task;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.AbstractIntegrationTest;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.dto.CreateProjectRequest;
import team.sprintory_backend.project.dto.ProjectResponse;
import team.sprintory_backend.project.dto.UpdateProjectRequest;
import team.sprintory_backend.project.models.ProjectMember;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectBoardService;
import team.sprintory_backend.project.services.ProjectService;
import team.sprintory_backend.task.api.TaskListOrder;
import team.sprintory_backend.task.api.TaskSearchFilters;
import team.sprintory_backend.task.dto.CreateTaskRequest;
import team.sprintory_backend.task.dto.MoveTaskRequest;
import team.sprintory_backend.task.dto.ReorderBacklogRequest;
import team.sprintory_backend.task.dto.TaskResponse;
import team.sprintory_backend.task.dto.UpdateTaskRequest;
import team.sprintory_backend.task.models.TaskPriority;
import team.sprintory_backend.task.models.TaskType;
import team.sprintory_backend.task.services.TaskApiService;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;
import team.sprintory_backend.workspace.repositories.WorkspaceRepository;
import team.sprintory_backend.workspace.services.WorkspaceService;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tasks (story 9) against a real PostgreSQL: migration V6, keys, soft delete, the filter query,
 * board and backlog order, and the cascades. Docker must be running. Each test is rolled back.
 */
@Transactional
class TaskIntegrationTest extends AbstractIntegrationTest {

    private static final TaskSearchFilters ALL = new TaskSearchFilters(null, null, null, null, null, null);

    @Autowired private EntityManager entityManager;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectService projects;
    @Autowired private ProjectBoardService boards;
    @Autowired private TaskApiService tasks;
    @Autowired private WorkspaceService workspaces;

    private User owner;
    private User grace;
    private Workspace workspace;
    private ProjectResponse scrum;
    private ProjectResponse kanban;

    /**
     * Acme: Owner created both projects, so is their Project Manager. Grace is the Scrum
     * project's Product Owner (the only role that may reorder the backlog).
     */
    @BeforeEach
    void setUp() {
        owner = userRepository.save(user("Owner", "owner@example.com"));
        grace = userRepository.save(user("Grace", "grace@example.com"));
        workspace = workspaceRepository.save(Workspace.builder().name("Acme").slug("acme-tasks").build());
        join(owner);
        join(grace);
        scrum = projects.create(owner, workspace.getId(),
                new CreateProjectRequest("Mobile App", "MOB", ProjectTemplate.SCRUM, null));
        kanban = projects.create(owner, workspace.getId(),
                new CreateProjectRequest("Support", "SUP", ProjectTemplate.KANBAN, null));
        entityManager.persist(ProjectMember.builder().project(projectRepository.getReferenceById(scrum.id()))
                .user(grace).roles(new HashSet<>(Set.of(ProjectRole.PRODUCT_OWNER))).build());
        entityManager.flush();
    }

    // --- keys and soft delete ---

    @Test
    void keysAreSequentialPerProjectAndNeverReused() {
        TaskResponse first = create(scrum, "First");
        TaskResponse second = create(scrum, "Second");
        assertEquals("MOB-1", first.key());
        assertEquals("MOB-2", second.key());

        tasks.delete(owner, scrum.id(), second.id());
        // Saving the project must not write back its stale in-memory counter (updatable = false).
        projects.update(owner, scrum.id(), new UpdateProjectRequest("Mobile App v2", null));
        entityManager.flush();
        entityManager.clear();

        assertEquals("MOB-3", create(scrum, "Third").key());
        assertEquals("SUP-1", create(kanban, "Other project").key());
    }

    @Test
    void aDeletedTaskStaysInTheDatabaseButIsHiddenEverywhere() {
        TaskResponse kept = create(scrum, "Kept");
        TaskResponse deleted = create(scrum, "Deleted");

        tasks.delete(owner, scrum.id(), deleted.id());
        entityManager.flush();
        entityManager.clear();

        assertEquals(1, count("select count(*) from tasks where id = :id and deleted_at is not null", deleted.id()));
        assertThrows(ResourceNotFoundException.class, () -> tasks.get(owner, scrum.id(), deleted.id()));
        assertThrows(ResourceNotFoundException.class, () -> tasks.delete(owner, scrum.id(), deleted.id()));
        assertEquals(List.of(kept.key()), keys(scrum, ALL, TaskListOrder.NEWEST));
        assertEquals(List.of(kept.key()), keys(scrum, ALL, TaskListOrder.BOARD));
        assertEquals(List.of(kept.key()), keys(scrum, ALL, TaskListOrder.BACKLOG));
    }

    @Test
    void aTaskIdFromAnotherProjectIsNotFound() {
        TaskResponse task = create(kanban, "Support ticket");

        assertThrows(ResourceNotFoundException.class, () -> tasks.get(owner, scrum.id(), task.id()));
    }

    // --- defaults ---

    @Test
    void newTasksStartInTheFirstColumnOfTheirTemplate() {
        TaskResponse scrumTask = create(scrum, "Scrum task");
        TaskResponse kanbanTask = create(kanban, "Kanban task");

        assertEquals("To Do", scrumTask.statusName());
        assertNull(scrumTask.sprintId(), "a new Scrum task is in the backlog: in no sprint");
        assertEquals("Backlog", kanbanTask.statusName());
        assertEquals(TaskType.TASK, scrumTask.type());
        assertEquals(TaskPriority.MEDIUM, scrumTask.priority());
        assertNotNull(scrumTask.createdAt());
    }

    // --- filters and search ---

    @Test
    void filtersCombineAndQMatchesTitleOrKeyLiterally() {
        tasks.create(owner, scrum.id(), new CreateTaskRequest("Login fails", TaskType.BUG, TaskPriority.HIGH,
                grace.getId(), 3, List.of("backend", "R09"), null));                                   // MOB-1
        tasks.create(owner, scrum.id(), new CreateTaskRequest("Signup page", null, null,
                null, null, List.of("backend", "frontend"), null));                                    // MOB-2
        tasks.create(owner, scrum.id(), new CreateTaskRequest("Discount 50% off", TaskType.STORY, null,
                null, null, List.of("frontend"), null));                                               // MOB-3
        entityManager.flush();
        entityManager.clear();

        assertEquals(List.of("MOB-2", "MOB-1"), keys(scrum, filters(null, null, null, null, "BACKEND"), TaskListOrder.NEWEST));
        assertEquals(List.of("MOB-3", "MOB-2"), keys(scrum, filters(null, null, null, null, "frontend"), TaskListOrder.NEWEST),
                "one row per task, whatever its labels");
        assertEquals(List.of("MOB-1"), keys(scrum, filters(null, "BUG", null, null, "backend"), TaskListOrder.NEWEST));
        assertEquals(List.of("MOB-1"), keys(scrum, filters(null, null, "HIGH", grace.getId(), null), TaskListOrder.NEWEST));
        assertEquals(List.of("MOB-1"), keys(scrum, filters("LOGIN", null, null, null, null), TaskListOrder.NEWEST));
        assertEquals(List.of("MOB-3"), keys(scrum, filters("mob-3", null, null, null, null), TaskListOrder.NEWEST));
        assertEquals(List.of("MOB-3"), keys(scrum, filters("50%", null, null, null, null), TaskListOrder.NEWEST));
        assertEquals(List.of("MOB-3"), keys(scrum, filters("%", null, null, null, null), TaskListOrder.NEWEST),
                "% is a literal, not a wildcard");
        assertEquals(List.of(), keys(scrum, filters("_", null, null, null, null), TaskListOrder.NEWEST));
        Long toDo = columns(scrum).get("To Do");
        assertEquals(3, tasks.list(owner, scrum.id(), new TaskSearchFilters(null, null, null, toDo, null, null),
                TaskListOrder.NEWEST, PageRequest.of(0, 2)).totalElements());
    }

    // --- board order ---

    @Test
    void boardOrderFollowsMovesInsideAndAcrossColumns() {
        Map<String, Long> column = columns(kanban);
        TaskResponse a = create(kanban, "A");                                                          // SUP-1
        TaskResponse b = create(kanban, "B");                                                          // SUP-2
        TaskResponse c = create(kanban, "C");                                                          // SUP-3
        assertEquals(List.of("SUP-1", "SUP-2", "SUP-3"), keys(kanban, ALL, TaskListOrder.BOARD));

        tasks.moveOnBoard(owner, kanban.id(), c.id(), new MoveTaskRequest(column.get("Backlog"), null));
        assertEquals(List.of("SUP-3", "SUP-1", "SUP-2"), keys(kanban, ALL, TaskListOrder.BOARD));

        tasks.moveOnBoard(owner, kanban.id(), a.id(), new MoveTaskRequest(column.get("To Do"), null));
        TaskResponse moved = tasks.moveOnBoard(owner, kanban.id(), b.id(),
                new MoveTaskRequest(column.get("To Do"), a.id()));
        assertEquals("To Do", moved.statusName());
        // Backlog column first, then To Do: [C] then [A, B].
        assertEquals(List.of("SUP-3", "SUP-1", "SUP-2"), keys(kanban, ALL, TaskListOrder.BOARD));
        assertEquals(List.of("SUP-1", "SUP-2"), keys(kanban,
                new TaskSearchFilters(null, null, null, column.get("To Do"), null, null), TaskListOrder.BOARD));

        // A status change through PATCH puts the task last in its new column.
        tasks.update(owner, kanban.id(), a.id(), new UpdateTaskRequest(null, null, null, column.get("Done"),
                null, null, null, null, false, false, false));
        tasks.update(owner, kanban.id(), c.id(), new UpdateTaskRequest(null, null, null, column.get("Done"),
                null, null, null, null, false, false, false));
        assertEquals(List.of("SUP-1", "SUP-3"), keys(kanban,
                new TaskSearchFilters(null, null, null, column.get("Done"), null, null), TaskListOrder.BOARD));
    }

    @Test
    void aMoveWithNoRoomLeftRenumbersTheColumn() {
        Map<String, Long> column = columns(kanban);
        TaskResponse a = create(kanban, "A");
        TaskResponse b = create(kanban, "B");
        TaskResponse c = create(kanban, "C");
        entityManager.flush();
        // Squeeze A and B together so nothing fits between them.
        setBoardPosition(a.id(), 10);
        setBoardPosition(b.id(), 11);
        entityManager.clear();

        tasks.moveOnBoard(owner, kanban.id(), c.id(), new MoveTaskRequest(column.get("Backlog"), a.id()));
        entityManager.flush();

        assertEquals(List.of("SUP-1", "SUP-3", "SUP-2"), keys(kanban, ALL, TaskListOrder.BOARD));
        assertEquals(List.of(1_000_000L, 2_000_000L, 3_000_000L), boardPositions(kanban.id()));
    }

    // --- backlog order ---

    @Test
    void theProductOwnerReordersTheBacklog() {
        TaskResponse x = create(scrum, "X");                                                           // MOB-1
        create(scrum, "Y");                                                                            // MOB-2
        TaskResponse z = create(scrum, "Z");                                                           // MOB-3
        assertEquals(List.of("MOB-1", "MOB-2", "MOB-3"), keys(scrum, ALL, TaskListOrder.BACKLOG));

        tasks.moveInBacklog(grace, scrum.id(), z.id(), new ReorderBacklogRequest(null));
        assertEquals(List.of("MOB-3", "MOB-1", "MOB-2"), keys(scrum, ALL, TaskListOrder.BACKLOG));

        tasks.moveInBacklog(grace, scrum.id(), x.id(), new ReorderBacklogRequest(keyToId(scrum, "MOB-2")));
        assertEquals(List.of("MOB-3", "MOB-2", "MOB-1"), keys(scrum, ALL, TaskListOrder.BACKLOG));

        // Per the roles matrix only the Product Owner holds MANAGE_BACKLOG; the Project Manager does not.
        assertThrows(AccessDeniedException.class,
                () -> tasks.moveInBacklog(owner, scrum.id(), z.id(), new ReorderBacklogRequest(null)));
    }

    // --- assignees who leave ---

    @Test
    void removingSomeoneFromAProjectUnassignsThemThereOnly() {
        TaskResponse scrumTask = assignedToGraceIn(scrum);
        TaskResponse kanbanTask = assignedToGraceIn(kanban);

        projects.removeMember(owner, scrum.id(), grace.getId());
        entityManager.flush();
        entityManager.clear();

        assertNull(tasks.get(owner, scrum.id(), scrumTask.id()).assigneeId());
        assertEquals(grace.getId(), tasks.get(owner, kanban.id(), kanbanTask.id()).assigneeId());
    }

    @Test
    void leavingTheWorkspaceUnassignsThemInAllItsProjects() {
        TaskResponse scrumTask = assignedToGraceIn(scrum);
        TaskResponse kanbanTask = assignedToGraceIn(kanban);

        workspaces.removeMember(grace, workspace.getId(), grace.getId());
        entityManager.flush();
        entityManager.clear();

        assertNull(tasks.get(owner, scrum.id(), scrumTask.id()).assigneeId());
        assertNull(tasks.get(owner, kanban.id(), kanbanTask.id()).assigneeId());
    }

    /** Puts Grace in the Kanban project too (she is already in Scrum) and assigns her a task there. */
    private TaskResponse assignedToGraceIn(ProjectResponse project) {
        if (project == kanban) {
            entityManager.persist(ProjectMember.builder().project(projectRepository.getReferenceById(kanban.id()))
                    .user(grace).roles(new HashSet<>(Set.of(ProjectRole.MEMBER))).build());
            entityManager.flush();
        }
        TaskResponse task = tasks.create(owner, project.id(), new CreateTaskRequest("For Grace", null, null,
                grace.getId(), null, null, null));
        entityManager.flush();
        return task;
    }

    // --- cascades ---

    @Test
    void deletingAUserClearsTheirAssignments() {
        TaskResponse task = tasks.create(owner, scrum.id(), new CreateTaskRequest("Assigned", null, null,
                grace.getId(), null, null, null));
        entityManager.flush();
        entityManager.clear();

        userRepository.deleteById(grace.getId());
        entityManager.flush();
        entityManager.clear();

        assertNull(tasks.get(owner, scrum.id(), task.id()).assigneeId());
    }

    @Test
    void deletingTheWorkspaceRemovesItsTasksLabelsAndColumns() {
        tasks.create(owner, scrum.id(), new CreateTaskRequest("Labelled", null, null, grace.getId(), 1,
                List.of("backend"), "text"));
        create(kanban, "Kanban task");
        tasks.delete(owner, kanban.id(), create(kanban, "Deleted").id());
        entityManager.flush();
        entityManager.clear();

        // The same path as WorkspaceService.delete. The task -> column foreign key has no cascade
        // and is checked at the end of the statement, after both have been cascaded away.
        Workspace loaded = workspaceMemberRepository.findByWorkspaceIdAndUserId(workspace.getId(), owner.getId())
                .orElseThrow().getWorkspace();
        workspaceRepository.delete(loaded);
        entityManager.flush();
        entityManager.clear();

        assertEquals(0, count("select count(*) from tasks where project_id in (:id, " + kanban.id() + ")", scrum.id()));
        assertEquals(0, count("select count(*) from task_labels", null));
        assertEquals(0, count("select count(*) from project_board_columns where project_id = :id", scrum.id()));
    }

    // --- helpers ---

    private TaskResponse create(ProjectResponse project, String title) {
        return tasks.create(owner, project.id(), new CreateTaskRequest(title, null, null, null, null, null, null));
    }

    private List<String> keys(ProjectResponse project, TaskSearchFilters filters, TaskListOrder order) {
        return tasks.list(owner, project.id(), filters, order, PageRequest.of(0, 50)).content().stream()
                .map(TaskResponse::key).toList();
    }

    private Long keyToId(ProjectResponse project, String key) {
        return tasks.list(owner, project.id(), filters(key, null, null, null, null), TaskListOrder.NEWEST,
                PageRequest.of(0, 1)).content().get(0).id();
    }

    private static TaskSearchFilters filters(String q, String type, String priority, Long assigneeId, String label) {
        return new TaskSearchFilters(q, type, priority, null, assigneeId, label);
    }

    private Map<String, Long> columns(ProjectResponse project) {
        return boards.get(owner, project.id()).columns().stream()
                .collect(Collectors.toMap(c -> c.name(), c -> c.id()));
    }

    private void setBoardPosition(Long taskId, long position) {
        entityManager.createNativeQuery("update tasks set board_position = :position where id = :id")
                .setParameter("position", position).setParameter("id", taskId).executeUpdate();
    }

    @SuppressWarnings("unchecked")
    private List<Long> boardPositions(Long projectId) {
        return ((List<Number>) entityManager.createNativeQuery(
                        "select board_position from tasks where project_id = :id order by board_position")
                .setParameter("id", projectId).getResultList()).stream().map(Number::longValue).toList();
    }

    /** Counts rows straight from the database, past the persistence context. */
    private long count(String sql, Long id) {
        var query = entityManager.createNativeQuery(sql);
        if (id != null) {
            query.setParameter("id", id);
        }
        return ((Number) query.getSingleResult()).longValue();
    }

    private void join(User user) {
        workspaceMemberRepository.save(WorkspaceMember.builder().workspace(workspace).user(user)
                .role(Role.WORKSPACE_MEMBER).build());
    }

    private static User user(String firstName, String email) {
        return User.builder().firstName(firstName).lastName("Tester").email(email).enabled(true).build();
    }
}