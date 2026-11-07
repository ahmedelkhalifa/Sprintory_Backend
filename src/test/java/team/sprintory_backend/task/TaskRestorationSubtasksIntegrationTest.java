package team.sprintory_backend.task;

import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.AbstractIntegrationTest;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.dto.CreateProjectRequest;
import team.sprintory_backend.project.dto.CreateBoardColumnRequest;
import team.sprintory_backend.project.dto.DeleteBoardColumnRequest;
import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.project.models.ProjectMember;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectBoardService;
import team.sprintory_backend.project.services.ProjectService;
import team.sprintory_backend.task.api.TaskHistoryOrder;
import team.sprintory_backend.task.api.TaskListOrder;
import team.sprintory_backend.task.api.TaskSearchFilters;
import team.sprintory_backend.task.dto.CreateSubtaskRequest;
import team.sprintory_backend.task.dto.CreateTaskRequest;
import team.sprintory_backend.task.dto.MoveTaskRequest;
import team.sprintory_backend.task.dto.TaskResponse;
import team.sprintory_backend.task.dto.UpdateSubtaskRequest;
import team.sprintory_backend.task.dto.UpdateTaskRequest;
import team.sprintory_backend.task.exceptions.OpenSubtasksConfirmationRequiredException;
import team.sprintory_backend.task.models.TaskHistoryField;
import team.sprintory_backend.task.services.TaskApiService;
import team.sprintory_backend.task.services.SubtaskService;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;
import team.sprintory_backend.workspace.repositories.WorkspaceRepository;

import static org.junit.jupiter.api.Assertions.*;

/** PMP-131/132: real Postgres, V10 migration, permissions, cascade provenance and board invariants. */
@Transactional
class TaskRestorationSubtasksIntegrationTest extends AbstractIntegrationTest {
    private static final TaskSearchFilters ALL = new TaskSearchFilters(null,null,null,null,null,null);

    @Autowired private EntityManager em;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository workspaceMembers;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectService projectService;
    @Autowired private ProjectBoardService boards;
    @Autowired private TaskApiService tasks;
    @Autowired private SubtaskService subtasks;

    private User owner;
    private User viewer;
    private User outsider;
    private Long projectId;

    @BeforeEach
    void setUp() {
        owner = createUser("Owner", "restore-owner@example.com");
        viewer = createUser("Viewer", "restore-viewer@example.com");
        outsider = createUser("Other", "restore-other@example.com");
        Workspace workspace = workspaces.save(Workspace.builder().name("Restore test")
                .slug("restore-subtasks-test").build());
        for (User user : List.of(owner, viewer, outsider)) {
            workspaceMembers.save(WorkspaceMember.builder().workspace(workspace).user(user)
                    .role(Role.WORKSPACE_MEMBER).build());
        }
        projectId = projectService.create(owner, workspace.getId(),
                new CreateProjectRequest("Product", "RST", ProjectTemplate.KANBAN, null)).id();
        em.persist(ProjectMember.builder().project(projectRepository.getReferenceById(projectId))
                .user(viewer).roles(new HashSet<>(Set.of(ProjectRole.VIEWER))).build());
        em.flush();
    }

    @Test
    void restoreDeletedTaskKeepsItsKeyAndCreatesAuditEventOnce() {
        TaskResponse first = parent("Restore me");
        tasks.delete(owner, projectId, first.id());
        em.flush(); em.clear();
        assertThrows(ResourceNotFoundException.class, () -> tasks.get(owner, projectId, first.id()));

        TaskResponse restored = tasks.restore(owner, projectId, first.id());
        assertEquals(first.key(), restored.key());
        assertEquals("Restore me", tasks.get(owner, projectId, first.id()).title());
        assertThrows(ResourceNotFoundException.class, () -> tasks.restore(owner, projectId, first.id()));
        assertEquals(1, count("select count(*) from audit_log_entries where action='TASK_RESTORED' and target_id = :id", first.id()));
        assertNotEquals(first.key(), parent("Next task").key());
    }

    @Test
    void restoringADeletedTaskWhoseOldColumnWasDeletedUsesActiveFirstColumn() {
        var cols = boards.get(owner, projectId).columns();
        Long inProgress = cols.stream().filter(c -> c.name().equals("In Progress")).findFirst().orElseThrow().id();
        TaskResponse task = parent("Old column");
        tasks.moveOnBoard(owner, projectId, task.id(), new MoveTaskRequest(inProgress, null));
        tasks.delete(owner, projectId, task.id());
        em.flush(); em.clear();
        boards.delete(owner, projectId, inProgress, new DeleteBoardColumnRequest(null));
        em.flush(); em.clear();
        assertEquals("Backlog", tasks.restore(owner, projectId, task.id()).statusName());
    }

    @Test
    void subtaskKeyProgressAndBoardExclusionAndStatusUpdates() {
        TaskResponse p = parent("Parent");
        Long done = boards.get(owner, projectId).columns().stream().filter(c -> c.name().equals("Done"))
                .findFirst().orElseThrow().id();
        TaskResponse child = subtasks.create(owner, projectId, p.id(),
                new CreateSubtaskRequest("Child one", viewer.getId(), null, 60));
        TaskResponse child2 = subtasks.create(owner, projectId, p.id(),
                new CreateSubtaskRequest("Child two", null, null, null));
        assertEquals("RST-2", child.key());
        assertNull(child.estimate());
        assertEquals(60, child.estimateMinutes());
        assertEquals(p.id(), child.parentTaskId());
        assertEquals(2, tasks.get(owner, projectId, p.id()).subtaskTotal());
        assertEquals(0, tasks.get(owner, projectId, p.id()).subtaskDone());
        assertEquals(List.of(p.key()), tasks.list(owner, projectId, ALL, TaskListOrder.NEWEST,
                PageRequest.of(0, 20)).content().stream().map(TaskResponse::key).toList());
        assertEquals(1, boards.get(owner, projectId).columns().stream()
                .filter(c -> c.name().equals("Backlog")).findFirst().orElseThrow().taskCount());

        subtasks.update(owner, projectId, p.id(), child.id(),
                new UpdateSubtaskRequest("Finished child", null, done, null, false, false));
        assertEquals(1, tasks.get(owner, projectId, p.id()).subtaskDone());
        assertEquals(1, statusMoves(child.id()));
        assertThrows(BadRequestException.class, () -> subtasks.create(owner, projectId, child.id(),
                new CreateSubtaskRequest("Grandchild", null, null, null)));
        assertThrows(BadRequestException.class, () -> tasks.moveOnBoard(owner, projectId, child.id(),
                new MoveTaskRequest(done, null)));
        assertEquals(2, subtasks.list(viewer, projectId, p.id()).size());
        assertNotNull(child2.key());
    }

    @Test
    void parentDoneRequiresExplicitConfirmationWithOpenChildren() {
        TaskResponse p = parent("Parent");
        subtasks.create(owner, projectId, p.id(), new CreateSubtaskRequest("Open child", null, null, null));
        Long done = boards.get(owner, projectId).columns().stream().filter(c -> c.name().equals("Done"))
                .findFirst().orElseThrow().id();
        assertThrows(OpenSubtasksConfirmationRequiredException.class,
                () -> tasks.moveOnBoard(owner, projectId, p.id(), new MoveTaskRequest(done, null)));
        assertNotEquals("Done", tasks.get(owner, projectId, p.id()).statusName());
        assertEquals("Done", tasks.moveOnBoard(owner, projectId, p.id(),
                new MoveTaskRequest(done, null, true)).statusName());
    }

    @Test
    void deletingRestoringParentDoesNotResurrectPreviouslyManuallyDeletedChild() {
        TaskResponse p = parent("Parent");
        TaskResponse keepDeleted = subtasks.create(owner, projectId, p.id(),
                new CreateSubtaskRequest("Manual deletion", null, null, null));
        TaskResponse cascade = subtasks.create(owner, projectId, p.id(),
                new CreateSubtaskRequest("Cascade deletion", null, null, null));
        tasks.delete(owner, projectId, keepDeleted.id());
        tasks.delete(owner, projectId, p.id());
        em.flush(); em.clear();
        assertThrows(ResourceNotFoundException.class, () -> tasks.get(owner, projectId, cascade.id()));
        TaskResponse restored = tasks.restore(owner, projectId, p.id());
        assertEquals(1, restored.subtaskTotal());
        assertEquals(List.of(cascade.key()), subtasks.list(owner, projectId, p.id()).stream()
                .map(TaskResponse::key).toList());
        assertThrows(ResourceNotFoundException.class, () -> tasks.get(owner, projectId, keepDeleted.id()));
        assertEquals(1, count("select count(*) from tasks where id = :id and deleted_at is not null",
                keepDeleted.id()));
        tasks.restore(owner, projectId, keepDeleted.id());
        assertEquals(2, subtasks.list(owner, projectId, p.id()).size());
    }

    @Test
    void viewerCannotWriteAndOutsiderCannotSeeOrRestore() {
        TaskResponse p = parent("Secret");
        assertThrows(AccessDeniedException.class, () -> subtasks.create(viewer, projectId, p.id(),
                new CreateSubtaskRequest("Unauthorized", null, null, null)));
        assertThrows(AccessDeniedException.class, () -> tasks.restore(viewer, projectId, p.id()));
        assertThrows(ResourceNotFoundException.class, () -> subtasks.list(outsider, projectId, p.id()));
        assertThrows(ResourceNotFoundException.class, () -> tasks.restore(outsider, projectId, p.id()));
    }

    @Test
    void deletingColumnWithSubtaskMovesChildToTargetAndRetainsHistory() {
        TaskResponse p = parent("Parent");
        var ids = boards.get(owner, projectId).columns();
        Long review = boards.add(owner, projectId, new CreateBoardColumnRequest("Review",
                BoardColumnCategory.IN_PROGRESS, ids.get(2).id())).columns().stream()
                .filter(c -> c.name().equals("Review")).findFirst().orElseThrow().id();
        Long toDo = ids.stream().filter(c -> c.name().equals("To Do")).findFirst().orElseThrow().id();
        TaskResponse child = subtasks.create(owner, projectId, p.id(),
                new CreateSubtaskRequest("Review child", null, review, null));
        boards.delete(owner, projectId, review, new DeleteBoardColumnRequest(toDo));
        em.flush(); em.clear();
        assertEquals("To Do", subtasks.list(owner, projectId, p.id()).get(0).statusName());
        assertEquals(1, statusMoves(child.id()));
    }

    @Test
    void deletingAColumnIntoDoneNeedsConfirmationForParentsWithOpenSubtasks() {
        TaskResponse p = parent("Parent");
        var ids = boards.get(owner, projectId).columns();
        Long review = boards.add(owner, projectId, new CreateBoardColumnRequest("Review",
                BoardColumnCategory.IN_PROGRESS, ids.get(2).id())).columns().stream()
                .filter(c -> c.name().equals("Review")).findFirst().orElseThrow().id();
        Long done = ids.stream().filter(c -> c.name().equals("Done")).findFirst().orElseThrow().id();
        tasks.moveOnBoard(owner, projectId, p.id(), new MoveTaskRequest(review, null));
        subtasks.create(owner, projectId, p.id(), new CreateSubtaskRequest("Open child", null, null, null));

        assertThrows(OpenSubtasksConfirmationRequiredException.class,
                () -> boards.delete(owner, projectId, review, new DeleteBoardColumnRequest(done)));
        assertEquals("Review", tasks.get(owner, projectId, p.id()).statusName());

        boards.delete(owner, projectId, review, new DeleteBoardColumnRequest(done, true));
        em.flush(); em.clear();
        assertEquals("Done", tasks.get(owner, projectId, p.id()).statusName());
    }

    /** How many of the task's history entries are status moves. */
    private long statusMoves(Long taskId) {
        return tasks.history(owner, projectId, taskId, TaskHistoryOrder.NEWEST, PageRequest.of(0, 100)).content().stream()
                .filter(entry -> entry.field() == TaskHistoryField.STATUS).count();
    }

    private TaskResponse parent(String title) {
        return tasks.create(owner, projectId, new CreateTaskRequest(title, null, null, null, null, null, null));
    }

    private User createUser(String name, String email) {
        return users.save(User.builder().firstName(name).lastName("Tester").email(email).enabled(true).build());
    }

    private long count(String sql, Long id) {
        return ((Number) em.createNativeQuery(sql).setParameter("id", id).getSingleResult()).longValue();
    }
}
