package team.sprintory_backend.task.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectBoard;
import team.sprintory_backend.project.models.ProjectBoardColumn;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.repositories.ProjectBoardColumnRepository;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.project.services.ProjectAccessResult;
import team.sprintory_backend.project.strategy.KanbanBoardStrategy;
import team.sprintory_backend.project.strategy.ProjectBoardStrategies;
import team.sprintory_backend.project.strategy.ScrumBoardStrategy;
import team.sprintory_backend.task.api.TaskListOrder;
import team.sprintory_backend.task.api.TaskSearchFilters;
import team.sprintory_backend.task.dto.CreateTaskRequest;
import team.sprintory_backend.task.dto.MoveTaskRequest;
import team.sprintory_backend.task.dto.ReorderBacklogRequest;
import team.sprintory_backend.task.dto.TaskResponse;
import team.sprintory_backend.task.dto.UpdateTaskRequest;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.models.TaskPriority;
import team.sprintory_backend.task.models.TaskType;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Pure service tests: access and every repository are mocked, so no database or Spring context. */
class TaskApiServiceTest {
    private static final Long PROJECT_ID = 3L;

    private ProjectAccess access;
    private TaskRepository taskRepository;
    private ProjectRepository projectRepository;
    private ProjectMemberRepository projectMemberRepository;
    private ProjectBoardColumnRepository columnRepository;
    private UserRepository userRepository;
    private TaskApiService service;
    private User user;
    private Project project;
    private ProjectBoardColumn firstColumn;

    @BeforeEach
    void setUp() {
        access = mock(ProjectAccess.class);
        taskRepository = mock(TaskRepository.class);
        projectRepository = mock(ProjectRepository.class);
        projectMemberRepository = mock(ProjectMemberRepository.class);
        columnRepository = mock(ProjectBoardColumnRepository.class);
        userRepository = mock(UserRepository.class);
        service = new TaskApiService(access, taskRepository, projectRepository, projectMemberRepository,
                columnRepository, userRepository,
                new ProjectBoardStrategies(List.of(new ScrumBoardStrategy(), new KanbanBoardStrategy())));
        user = User.builder().id(42L).firstName("Ada").lastName("Lovelace")
                .email("ada@example.com").build();

        project = Project.builder().id(PROJECT_ID).name("Mobile App").key("MOB")
                .template(ProjectTemplate.KANBAN).build();
        ProjectBoard board = new ProjectBoard(project);
        firstColumn = column(board, 11L, "Backlog", 0);
        when(columnRepository.findByBoardProjectIdOrderByPositionAscIdAsc(PROJECT_ID))
                .thenReturn(List.of(firstColumn, column(board, 12L, "To Do", 1)));
        when(access.requireActive(PROJECT_ID, user, ProjectPermission.EDIT_TASKS))
                .thenReturn(new ProjectAccessResult(project, null, null, Set.of(ProjectPermission.EDIT_TASKS)));
        when(projectRepository.findLastTaskNumber(PROJECT_ID)).thenReturn(42);
        when(taskRepository.save(any(Task.class))).thenAnswer(call -> {
            Task saved = call.getArgument(0);
            saved.setId(500L);
            return saved;
        });
    }

    // --- create ---

    @Test
    void createGivesTheNextKeyTheDefaultsAndTheBoardsFirstColumn() {
        when(taskRepository.findMaxBoardPosition(PROJECT_ID, 11L)).thenReturn(3 * TaskRanks.GAP);

        TaskResponse response = service.create(user, PROJECT_ID,
                new CreateTaskRequest("  Fix login  ", null, null, null, null,
                        List.of(" backend ", "Backend", "R09"), "  "));

        // The counter UPDATE takes the project lock before the ranks are read.
        InOrder order = inOrder(projectRepository, taskRepository);
        order.verify(projectRepository).incrementTaskNumber(PROJECT_ID);
        order.verify(projectRepository).findLastTaskNumber(PROJECT_ID);
        order.verify(taskRepository).findMaxBoardPosition(PROJECT_ID, 11L);
        assertEquals(4 * TaskRanks.GAP, savedTask().getBoardPosition(), "last in its column");
        assertEquals(TaskRanks.GAP, savedTask().getBacklogRank(), "first task of the backlog");
        assertEquals("MOB-42", response.key());
        assertEquals(42, savedTask().getNumber());
        assertEquals("Fix login", response.title());
        assertEquals(TaskType.TASK, response.type());
        assertEquals(TaskPriority.MEDIUM, response.priority());
        assertEquals(11L, response.statusId());
        assertEquals("Backlog", response.statusName());
        assertNull(response.assigneeId());
        assertNull(response.sprintId());
        assertNull(response.description());
        assertEquals(List.of("R09", "backend"), response.labels());
        assertEquals(PROJECT_ID, response.projectId());
    }

    @Test
    void createKeepsTheGivenFieldsAndAcceptsAProjectMemberAsAssignee() {
        User assignee = User.builder().id(7L).build();
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, 7L)).thenReturn(true);
        when(userRepository.getReferenceById(7L)).thenReturn(assignee);

        TaskResponse response = service.create(user, PROJECT_ID, new CreateTaskRequest("Crash on start",
                TaskType.BUG, TaskPriority.HIGHEST, 7L, 3, null, "Steps to reproduce"));

        assertEquals(TaskType.BUG, response.type());
        assertEquals(TaskPriority.HIGHEST, response.priority());
        assertEquals(7L, response.assigneeId());
        assertEquals(3, response.estimate());
        assertEquals("Steps to reproduce", response.description());
        assertEquals(List.of(), response.labels());
    }

    @Test
    void createRefusesAnAssigneeWhoIsNotInTheProject() {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, 7L)).thenReturn(false);

        BadRequestException e = assertThrows(BadRequestException.class, () -> service.create(user, PROJECT_ID,
                new CreateTaskRequest("Fix login", null, null, 7L, null, null, null)));

        assertEquals("The assignee must be a member of this project.", e.getMessage());
        verify(projectRepository, never()).incrementTaskNumber(anyLong());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void createIsRefusedBeforeAnythingIsWrittenWhenTheCallerMayNotEditTasks() {
        doThrow(new AccessDeniedException("viewer"))
                .when(access).requireActive(PROJECT_ID, user, ProjectPermission.EDIT_TASKS);

        assertThrows(AccessDeniedException.class, () -> service.create(user, PROJECT_ID,
                new CreateTaskRequest("Fix bug", null, null, null, null, null, null)));
        verify(projectRepository, never()).incrementTaskNumber(anyLong());
        verify(taskRepository, never()).save(any());
    }

    // --- get ---

    @Test
    void getReturnsTheTaskOfThisProject() {
        Task task = givenTask();

        TaskResponse response = service.get(user, PROJECT_ID, 5L);

        verify(access).require(PROJECT_ID, user, ProjectPermission.VIEW_WORK);
        assertEquals(task.getKey(), response.key());
        assertEquals("To Do", response.statusName());
    }

    @Test
    void getGivesNotFoundForAMissingDeletedOrOtherProjectsTask() {
        when(taskRepository.findByIdAndProjectId(anyLong(), anyLong())).thenReturn(Optional.empty());

        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class,
                () -> service.get(user, PROJECT_ID, 5L));
        assertEquals("Task not found.", e.getMessage());
    }

    // --- update ---

    @Test
    void updateChangesOnlyTheFieldsThatAreSent() {
        Task task = givenTask();
        ProjectBoardColumn done = column(new ProjectBoard(project), 14L, "Done", 3);
        when(columnRepository.findByIdAndBoardProjectId(14L, PROJECT_ID)).thenReturn(Optional.of(done));

        TaskResponse response = service.update(user, PROJECT_ID, 5L, new UpdateTaskRequest(" New title ",
                TaskType.STORY, TaskPriority.LOW, 14L, null, 8, List.of("ui"), "Details", false, false, false));

        verify(access).requireActive(PROJECT_ID, user, ProjectPermission.EDIT_TASKS);
        assertEquals("New title", response.title());
        assertEquals(TaskType.STORY, response.type());
        assertEquals(TaskPriority.LOW, response.priority());
        assertEquals(14L, response.statusId());
        assertEquals(TaskRanks.GAP, task.getBoardPosition(), "last in the new, empty column");
        verify(projectRepository).lockById(PROJECT_ID);
        assertEquals(8, response.estimate());
        assertEquals(List.of("ui"), response.labels());
        assertEquals("Details", response.description());
        assertEquals(7L, response.assigneeId(), "assignee was not sent, so it is unchanged");
        assertEquals("MOB-5", task.getKey());
    }

    @Test
    void updateWithNothingSentChangesNothing() {
        Task task = givenTask();

        service.update(user, PROJECT_ID, 5L, new UpdateTaskRequest(null, null, null, null, null,
                null, null, null, false, false, false));

        assertEquals("Old title", task.getTitle());
        assertEquals(TaskType.BUG, task.getType());
        assertEquals(2, task.getEstimate());
        assertEquals(Set.of("backend"), task.getLabels());
        assertEquals("Old description", task.getDescription());
        assertEquals(7L, task.getAssignee().getId());
    }

    @Test
    void updateClearFlagsAndAnEmptyLabelListClearTheirFields() {
        Task task = givenTask();

        service.update(user, PROJECT_ID, 5L, new UpdateTaskRequest(null, null, null, null, null,
                null, List.of(), null, true, true, true));

        assertNull(task.getAssignee());
        assertNull(task.getEstimate());
        assertNull(task.getDescription());
        assertTrue(task.getLabels().isEmpty());
    }

    @Test
    void updateRefusesAStatusThatIsNotAColumnOfThisProject() {
        givenTask();
        when(columnRepository.findByIdAndBoardProjectId(99L, PROJECT_ID)).thenReturn(Optional.empty());

        BadRequestException e = assertThrows(BadRequestException.class, () -> service.update(user, PROJECT_ID, 5L,
                new UpdateTaskRequest(null, null, null, 99L, null, null, null, null, false, false, false)));
        assertEquals("The status must be a column of this project's board.", e.getMessage());
    }

    @Test
    void updateRefusesAnAssigneeWhoIsNotInTheProject() {
        givenTask();
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, 9L)).thenReturn(false);

        assertThrows(BadRequestException.class, () -> service.update(user, PROJECT_ID, 5L,
                new UpdateTaskRequest(null, null, null, null, 9L, null, null, null, false, false, false)));
    }

    // --- delete ---

    @Test
    void deleteOnlyMarksTheTaskAsDeleted() {
        Task task = givenTask();

        service.delete(user, PROJECT_ID, 5L);

        verify(access).requireActive(PROJECT_ID, user, ProjectPermission.EDIT_TASKS);
        assertNotNull(task.getDeletedAt());
        verify(taskRepository).save(task);
        verify(taskRepository, never()).delete(any(Task.class));
    }

    @Test
    void deleteGivesNotFoundForATaskThatIsAlreadyDeleted() {
        when(taskRepository.findByIdAndProjectId(5L, PROJECT_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.delete(user, PROJECT_ID, 5L));
    }

    // --- order on the board ---

    @Test
    void moveOnBoardTakesTheMidpointBetweenItsNewNeighbours() {
        Task task = givenTask();
        ProjectBoardColumn done = givenColumn(14L, "Done");
        Task after = otherTask(6L, done, 2 * TaskRanks.GAP, 0);
        Task next = otherTask(8L, done, 3 * TaskRanks.GAP, 0);
        when(taskRepository.findNextInColumn(eq(PROJECT_ID), eq(14L), eq(5L), eq(2 * TaskRanks.GAP), any(Pageable.class)))
                .thenReturn(List.of(next));

        TaskResponse response = service.moveOnBoard(user, PROJECT_ID, 5L, new MoveTaskRequest(14L, 6L));

        verify(access).requireActive(PROJECT_ID, user, ProjectPermission.EDIT_TASKS);
        verify(projectRepository).lockById(PROJECT_ID);
        assertEquals(14L, response.statusId());
        assertEquals(2 * TaskRanks.GAP + TaskRanks.GAP / 2, task.getBoardPosition());
        assertEquals(2 * TaskRanks.GAP, after.getBoardPosition(), "neighbours are not touched");
    }

    @Test
    void moveOnBoardToTheTopOfAnEmptyColumn() {
        Task task = givenTask();
        givenColumn(14L, "Done");
        when(taskRepository.findNextInColumn(eq(PROJECT_ID), eq(14L), eq(5L), eq(Long.MIN_VALUE), any(Pageable.class)))
                .thenReturn(List.of());

        service.moveOnBoard(user, PROJECT_ID, 5L, new MoveTaskRequest(14L, null));

        assertEquals(TaskRanks.GAP, task.getBoardPosition());
    }

    @Test
    void moveOnBoardRenumbersTheColumnWhenThereIsNoRoom() {
        Task task = givenTask();
        ProjectBoardColumn done = givenColumn(14L, "Done");
        Task after = otherTask(6L, done, 10, 0);
        Task next = otherTask(8L, done, 11, 0);
        when(taskRepository.findNextInColumn(eq(PROJECT_ID), eq(14L), eq(5L), eq(10L), any(Pageable.class)))
                .thenReturn(List.of(next));
        when(taskRepository.findColumnInBoardOrder(PROJECT_ID, 14L)).thenReturn(List.of(after, next));

        service.moveOnBoard(user, PROJECT_ID, 5L, new MoveTaskRequest(14L, 6L));

        assertEquals(TaskRanks.GAP, after.getBoardPosition());
        assertEquals(2 * TaskRanks.GAP, task.getBoardPosition());
        assertEquals(3 * TaskRanks.GAP, next.getBoardPosition());
    }

    @Test
    void moveOnBoardRefusesANeighbourFromAnotherColumnOrTheTaskItself() {
        givenTask();
        givenColumn(14L, "Done");
        otherTask(6L, givenColumn(13L, "In Progress"), TaskRanks.GAP, 0);

        BadRequestException other = assertThrows(BadRequestException.class,
                () -> service.moveOnBoard(user, PROJECT_ID, 5L, new MoveTaskRequest(14L, 6L)));
        assertEquals("The task to place it after must be in the target column.", other.getMessage());
        BadRequestException itself = assertThrows(BadRequestException.class,
                () -> service.moveOnBoard(user, PROJECT_ID, 5L, new MoveTaskRequest(14L, 5L)));
        assertEquals("A task cannot be placed after itself.", itself.getMessage());
    }

    @Test
    void moveOnBoardRefusesAColumnOfAnotherProject() {
        givenTask();
        when(columnRepository.findByIdAndBoardProjectId(99L, PROJECT_ID)).thenReturn(Optional.empty());

        assertThrows(BadRequestException.class,
                () -> service.moveOnBoard(user, PROJECT_ID, 5L, new MoveTaskRequest(99L, null)));
    }

    // --- order in the backlog ---

    @Test
    void moveInBacklogTakesTheMidpointAndNeedsManageBacklog() {
        project.setTemplate(ProjectTemplate.SCRUM);
        givenBacklogAccess();
        Task task = givenTask();
        otherTask(6L, task.getStatus(), 0, TaskRanks.GAP);
        Task next = otherTask(8L, task.getStatus(), 0, 2 * TaskRanks.GAP);
        when(taskRepository.findNextInBacklog(eq(PROJECT_ID), eq(5L), eq(TaskRanks.GAP), any(Pageable.class)))
                .thenReturn(List.of(next));

        service.moveInBacklog(user, PROJECT_ID, 5L, new ReorderBacklogRequest(6L));

        verify(access).requireActive(PROJECT_ID, user, ProjectPermission.MANAGE_BACKLOG);
        verify(projectRepository).lockById(PROJECT_ID);
        assertEquals(TaskRanks.GAP + TaskRanks.GAP / 2, task.getBacklogRank());
    }

    @Test
    void moveInBacklogRenumbersTheBacklogWhenThereIsNoRoom() {
        project.setTemplate(ProjectTemplate.SCRUM);
        givenBacklogAccess();
        Task task = givenTask();
        Task after = otherTask(6L, task.getStatus(), 0, 7);
        Task next = otherTask(8L, task.getStatus(), 0, 8);
        when(taskRepository.findNextInBacklog(eq(PROJECT_ID), eq(5L), eq(7L), any(Pageable.class)))
                .thenReturn(List.of(next));
        when(taskRepository.findProjectInBacklogOrder(PROJECT_ID)).thenReturn(List.of(after, next, task));

        service.moveInBacklog(user, PROJECT_ID, 5L, new ReorderBacklogRequest(6L));

        assertEquals(TaskRanks.GAP, after.getBacklogRank());
        assertEquals(2 * TaskRanks.GAP, task.getBacklogRank());
        assertEquals(3 * TaskRanks.GAP, next.getBacklogRank());
    }

    @Test
    void moveInBacklogIsRefusedInKanban() {
        givenBacklogAccess();
        givenTask();

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.moveInBacklog(user, PROJECT_ID, 5L, new ReorderBacklogRequest(null)));
        assertEquals("This project has no backlog to reorder; move the task on the board.", e.getMessage());
        verify(projectRepository, never()).lockById(anyLong());
    }

    @Test
    void moveInBacklogIsRefusedWithoutManageBacklog() {
        doThrow(new AccessDeniedException("not the Product Owner"))
                .when(access).requireActive(PROJECT_ID, user, ProjectPermission.MANAGE_BACKLOG);

        assertThrows(AccessDeniedException.class,
                () -> service.moveInBacklog(user, PROJECT_ID, 5L, new ReorderBacklogRequest(null)));
    }

    // --- list ---

    @Test
    void listReturnsAPageInTheRequestedOrderAfterCheckingViewWork() {
        Task task = givenTask();
        when(taskRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(task), PageRequest.of(1, 10), 11));
        var filters = new TaskSearchFilters("mob", "BUG", null, null, null, null);

        PageResponse<TaskResponse> page = service.list(user, PROJECT_ID, filters, TaskListOrder.BOARD,
                PageRequest.of(1, 10));

        verify(access).require(PROJECT_ID, user, ProjectPermission.VIEW_WORK);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(taskRepository).findAll(any(Specification.class), pageable.capture());
        assertEquals(PageRequest.of(1, 10, TaskListOrder.BOARD.sort()), pageable.getValue());
        assertEquals(List.of("MOB-5"), page.content().stream().map(TaskResponse::key).toList());
        assertEquals(11, page.totalElements());
    }

    @Test
    void listIsRefusedBeforeQueryingWithoutViewWork() {
        doThrow(new AccessDeniedException("workspace admin, not in the project"))
                .when(access).require(PROJECT_ID, user, ProjectPermission.VIEW_WORK);

        assertThrows(AccessDeniedException.class, () -> service.list(user, PROJECT_ID,
                new TaskSearchFilters(null, null, null, null, null, null), TaskListOrder.NEWEST,
                PageRequest.of(0, 20)));
        verify(taskRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    /** MOB-5 in To Do: a bug with an assignee, estimate, label and description. */
    private Task givenTask() {
        Task task = Task.builder().id(5L).project(project).number(5).key("MOB-5").title("Old title")
                .type(TaskType.BUG).priority(TaskPriority.HIGH)
                .status(column(new ProjectBoard(project), 12L, "To Do", 1))
                .assignee(User.builder().id(7L).build()).estimate(2).description("Old description")
                .labels(new HashSet<>(Set.of("backend"))).build();
        when(taskRepository.findByIdAndProjectId(5L, PROJECT_ID)).thenReturn(Optional.of(task));
        when(taskRepository.saveAndFlush(any(Task.class))).thenAnswer(call -> call.getArgument(0));
        return task;
    }

    /** Another task of the project, findable by id, at the given board position and backlog rank. */
    private Task otherTask(Long id, ProjectBoardColumn status, long boardPosition, long backlogRank) {
        Task task = Task.builder().id(id).project(project).number(id.intValue()).key("MOB-" + id)
                .title("Task " + id).type(TaskType.TASK).priority(TaskPriority.MEDIUM).status(status)
                .boardPosition(boardPosition).backlogRank(backlogRank).build();
        when(taskRepository.findByIdAndProjectId(id, PROJECT_ID)).thenReturn(Optional.of(task));
        return task;
    }

    private ProjectBoardColumn givenColumn(Long id, String name) {
        ProjectBoardColumn column = column(new ProjectBoard(project), id, name, id.intValue());
        when(columnRepository.findByIdAndBoardProjectId(id, PROJECT_ID)).thenReturn(Optional.of(column));
        return column;
    }

    private void givenBacklogAccess() {
        when(access.requireActive(PROJECT_ID, user, ProjectPermission.MANAGE_BACKLOG))
                .thenReturn(new ProjectAccessResult(project, null, null, Set.of(ProjectPermission.MANAGE_BACKLOG)));
    }

    private Task savedTask() {
        ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(saved.capture());
        return saved.getValue();
    }

    private static ProjectBoardColumn column(ProjectBoard board, Long id, String name, int position) {
        ProjectBoardColumn column = new ProjectBoardColumn(board, name, position);
        column.setId(id);
        return column;
    }
}