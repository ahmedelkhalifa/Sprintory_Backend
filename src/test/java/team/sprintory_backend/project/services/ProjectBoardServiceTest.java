package team.sprintory_backend.project.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.project.dto.*;
import team.sprintory_backend.project.models.*;
import team.sprintory_backend.project.repositories.*;
import team.sprintory_backend.project.strategy.*;
import team.sprintory_backend.task.exceptions.OpenSubtasksConfirmationRequiredException;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.task.services.TaskStatusHistoryService;
import team.sprintory_backend.user.models.User;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** PMP-125 security and board-invariant tests without a database. */
class ProjectBoardServiceTest {
    private final Long projectId = 22L;
    private final User pm = User.builder().id(3L).email("pm@example.com").build();
    private Project project;
    private ProjectBoardService service;
    private ProjectBoardColumnRepository columns;
    private ProjectBoardRepository boards;
    private TaskRepository tasks;
    private ProjectAccess access;
    private TaskStatusHistoryService history;
    private ProjectRepository projects;
    private List<ProjectBoardColumn> live;

    @BeforeEach
    void setUp() {
        columns = mock(ProjectBoardColumnRepository.class);
        boards = mock(ProjectBoardRepository.class);
        tasks = mock(TaskRepository.class);
        access = mock(ProjectAccess.class);
        history = mock(TaskStatusHistoryService.class);
        projects = mock(ProjectRepository.class);
        project = Project.builder().id(projectId).template(ProjectTemplate.KANBAN).build();
        ProjectBoard board = new ProjectBoard(project);
        live = new ArrayList<>();
        live.add(column(board, 1L, "Backlog", 0));
        live.add(column(board, 2L, "To Do", 1));
        live.add(column(board, 3L, "In Progress", 2));
        live.add(column(board, 4L, "Done", 3));
        when(columns.findByBoardProjectIdOrderByPositionAscIdAsc(projectId))
                .thenAnswer(invocation -> List.copyOf(live));
        when(boards.existsById(projectId)).thenReturn(true);
        when(projects.lockById(projectId)).thenReturn(Optional.of(project));
        var permissions = Set.of(ProjectPermission.VIEW_WORK, ProjectPermission.CONFIGURE_BOARD);
        var granted = new ProjectAccessResult(project, null, null, permissions);
        when(access.requireActive(projectId, pm, ProjectPermission.CONFIGURE_BOARD)).thenReturn(granted);
        when(access.requireVisible(projectId, pm)).thenReturn(granted);
        service = new ProjectBoardService(boards, columns,
                new ProjectBoardStrategies(List.of(new KanbanBoardStrategy(), new ScrumBoardStrategy())),
                access, projects, tasks, history, mock(team.sprintory_backend.sprint.repositories.SprintRepository.class));
    }

    @Test
    void boardExposesCountsAndOverLimitFlag() {
        live.get(2).setWipLimit(3);
        when(tasks.countByProjectIdAndStatusIdAndParentIsNull(projectId, 3L)).thenReturn(4L);
        var column = service.get(pm, projectId).columns().get(2);
        assertEquals(BoardColumnCategory.IN_PROGRESS, column.category());
        assertEquals(4, column.taskCount());
        assertTrue(column.wipExceeded());
        when(tasks.countByProjectIdAndStatusIdAndParentIsNull(projectId, 3L)).thenReturn(3L);
        assertFalse(service.get(pm, projectId).columns().get(2).wipExceeded());
    }

    @Test
    void duplicateNameCannotBeAddedEvenWithDifferentCase() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.add(pm, projectId, new CreateBoardColumnRequest("  TO DO  ",
                        BoardColumnCategory.TODO, 3L)));
        assertTrue(e.getMessage().contains("already exists"));
        verify(columns, never()).saveAndFlush(any());
    }

    @Test
    void kanbanBacklogCannotMoveOrDeleteOrChangeCategory() {
        assertThrows(BadRequestException.class,
                () -> service.reorder(pm, projectId,
                        new ReorderBoardColumnsRequest(List.of(2L, 1L, 3L, 4L))));
        assertThrows(BadRequestException.class,
                () -> service.delete(pm, projectId, 1L, null));
        assertThrows(BadRequestException.class,
                () -> service.update(pm, projectId, 1L,
                        new UpdateBoardColumnRequest(null, BoardColumnCategory.DONE, null, false)));
    }

    @Test
    void cannotRemoveOrRecategorizeTheOnlyDoneColumn() {
        assertThrows(BadRequestException.class,
                () -> service.delete(pm, projectId, 4L, null));
        assertThrows(BadRequestException.class,
                () -> service.update(pm, projectId, 4L,
                        new UpdateBoardColumnRequest(null, BoardColumnCategory.TODO, null, false)));
    }

    @Test
    void wipLimitAllowedOnlyOnKanbanWorkColumns() {
        service.update(pm, projectId, 3L, new UpdateBoardColumnRequest(null, null, 3, false));
        assertEquals(3, live.get(2).getWipLimit());
        assertThrows(BadRequestException.class,
                () -> service.update(pm, projectId, 1L,
                        new UpdateBoardColumnRequest(null, null, 3, false)));
        assertThrows(BadRequestException.class,
                () -> service.update(pm, projectId, 4L,
                        new UpdateBoardColumnRequest(null, null, 3, false)));
        project.setTemplate(ProjectTemplate.SCRUM);
        assertThrows(BadRequestException.class,
                () -> service.update(pm, projectId, 3L,
                        new UpdateBoardColumnRequest(null, null, 2, false)));
    }

    @Test
    void deletingColumnMovesEveryTaskRecordsHistoryAndSoftDeletes() {
        // Delete a non-essential middle column with two tasks.
        Task first = new Task(); first.setId(51L); first.setStatus(live.get(2));
        Task second = new Task(); second.setId(52L); second.setStatus(live.get(2));
        when(tasks.findAllInColumnForDeletion(projectId, 3L)).thenReturn(List.of(first, second));
        when(tasks.findMaxBoardPosition(projectId, 2L)).thenReturn(1_000_000L);

        service.delete(pm, projectId, 3L, new DeleteBoardColumnRequest(2L));

        assertEquals(2L, first.getStatus().getId());
        assertEquals(2L, second.getStatus().getId());
        assertEquals(2_000_000L, first.getBoardPosition());
        assertEquals(3_000_000L, second.getBoardPosition());
        assertNotNull(live.get(2).getDeletedAt());
        verify(history, times(2)).record(any(Task.class), eq(live.get(2)), eq(live.get(1)), eq(pm));
        verify(tasks).saveAllAndFlush(anyList());
        verify(tasks, never()).countByParentIdInAndStatusCategoryNotAndStatusIdNot(any(), any(), any());
    }

    @Test
    void deletingIntoDoneRefusesParentsWithOpenSubtasksUnlessConfirmed() {
        Task parent = new Task(); parent.setId(51L); parent.setStatus(live.get(2));
        when(tasks.findAllInColumnForDeletion(projectId, 3L)).thenReturn(List.of(parent));
        when(tasks.countByParentIdInAndStatusCategoryNotAndStatusIdNot(List.of(51L),
                BoardColumnCategory.DONE, 3L)).thenReturn(2L);

        assertThrows(OpenSubtasksConfirmationRequiredException.class,
                () -> service.delete(pm, projectId, 3L, new DeleteBoardColumnRequest(4L)));
        assertSame(live.get(2), parent.getStatus());
        assertNull(live.get(2).getDeletedAt());
        verifyNoInteractions(history);

        service.delete(pm, projectId, 3L, new DeleteBoardColumnRequest(4L, true));
        assertSame(live.get(3), parent.getStatus());
        assertNotNull(live.get(2).getDeletedAt());
    }

    @Test
    void subtasksMovingAlongIntoDoneNeedNoConfirmation() {
        Task parent = new Task(); parent.setId(51L); parent.setStatus(live.get(2));
        Task child = new Task(); child.setId(52L); child.setStatus(live.get(2)); child.setParent(parent);
        when(tasks.findAllInColumnForDeletion(projectId, 3L)).thenReturn(List.of(parent, child));

        service.delete(pm, projectId, 3L, new DeleteBoardColumnRequest(4L));

        // Only the parent's other subtasks are counted; the child in the deleted column moves too.
        verify(tasks).countByParentIdInAndStatusCategoryNotAndStatusIdNot(List.of(51L),
                BoardColumnCategory.DONE, 3L);
        assertSame(live.get(3), parent.getStatus());
        assertSame(live.get(3), child.getStatus());
    }

    @Test
    void columnDeletionRequiresTargetWhenNonempty() {
        when(tasks.findAllInColumnForDeletion(projectId, 3L)).thenReturn(List.of(new Task()));
        assertThrows(BadRequestException.class,
                () -> service.delete(pm, projectId, 3L, null));
    }

    @Test
    void unauthorizedUsersCannotMutateColumns() {
        when(access.requireActive(projectId, pm, ProjectPermission.CONFIGURE_BOARD))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("Denied"));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.add(pm, projectId, new CreateBoardColumnRequest("QA",
                        BoardColumnCategory.IN_PROGRESS, 3L)));
        verify(projects, never()).lockById(anyLong());
    }

    private static ProjectBoardColumn column(ProjectBoard board, long id, String name, int position) {
        ProjectBoardColumn column = new ProjectBoardColumn(board, name, position);
        column.setId(id);
        return column;
    }
}
