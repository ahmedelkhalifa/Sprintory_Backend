package team.sprintory_backend.task.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.notification.events.TaskStatusChanged;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectBoard;
import team.sprintory_backend.project.models.ProjectBoardColumn;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.project.services.ProjectAccessResult;
import team.sprintory_backend.task.api.TaskHistoryOrder;
import team.sprintory_backend.task.dto.TaskHistoryEntryResponse;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.models.TaskHistoryEntry;
import team.sprintory_backend.task.models.TaskHistoryField;
import team.sprintory_backend.task.models.TaskHistoryType;
import team.sprintory_backend.task.repositories.TaskHistoryRepository;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.user.models.User;

/**
 * The task activity feed (docs/task-history-docs.md): each writer stores the right entry, a
 * status move also tells the watchers (R27), no-ops write nothing, and reading needs VIEW_WORK.
 */
class TaskStatusHistoryServiceTest {

    private static final Long PROJECT_ID = 3L;

    private TaskHistoryRepository historyRepository;
    private ProjectAccess access;
    private TaskRepository taskRepository;
    private ApplicationEventPublisher events;
    private TaskStatusHistoryService service;
    private final User user = User.builder().id(42L).firstName("Ada").lastName("Lovelace").build();
    private Task task;
    private ProjectBoardColumn todo;
    private ProjectBoardColumn done;

    @BeforeEach
    void setUp() {
        historyRepository = mock(TaskHistoryRepository.class);
        access = mock(ProjectAccess.class);
        taskRepository = mock(TaskRepository.class);
        events = mock(ApplicationEventPublisher.class);
        service = new TaskStatusHistoryService(historyRepository, access, taskRepository, events);
        Project project = Project.builder().id(PROJECT_ID).name("Mobile App").key("MOB").build();
        ProjectBoard board = new ProjectBoard(project);
        todo = column(board, 12L, "To Do", 1);
        done = column(board, 14L, "Done", 3);
        task = Task.builder().id(5L).project(project).key("MOB-5").title("Fix login").status(todo).build();
    }

    private static ProjectBoardColumn column(ProjectBoard board, Long id, String name, int position) {
        ProjectBoardColumn column = new ProjectBoardColumn(board, name, position);
        column.setId(id);
        return column;
    }

    private TaskHistoryEntry saved() {
        ArgumentCaptor<TaskHistoryEntry> entry = ArgumentCaptor.forClass(TaskHistoryEntry.class);
        verify(historyRepository).save(entry.capture());
        return entry.getValue();
    }

    // --- status ---

    @Test
    void aMoveToAnotherColumnWritesAStatusChangeAndPublishesIt() {
        service.record(task, todo, done, user);

        TaskHistoryEntry entry = saved();
        assertEquals(TaskHistoryType.FIELD_CHANGED, entry.getType());
        assertEquals(TaskHistoryField.STATUS, entry.getField());
        assertEquals("To Do", entry.getOldValue());
        assertEquals("Done", entry.getNewValue());
        assertEquals(task, entry.getTask());
        assertEquals(42L, entry.getActorId());
        assertEquals("Ada Lovelace", entry.getActorName());
        verify(events).publishEvent(new TaskStatusChanged(task, user, "To Do", "Done"));
    }

    @Test
    void stayingInTheSameColumnWritesAndPublishesNothing() {
        service.record(task, todo, todo, user);

        verifyNoInteractions(historyRepository, events);
    }

    @Test
    void statusCannotBeRecordedAsAPlainFieldChange() {
        assertThrows(IllegalArgumentException.class,
                () -> service.recordChange(task, TaskHistoryField.STATUS, "To Do", "Done", user));
        verifyNoInteractions(historyRepository);
    }

    // --- other writers ---

    @Test
    void creationIsRecorded() {
        service.recordCreated(task, user);

        TaskHistoryEntry entry = saved();
        assertEquals(TaskHistoryType.CREATED, entry.getType());
        assertNull(entry.getField());
        assertNull(entry.getOldValue());
        assertNull(entry.getNewValue());
    }

    @Test
    void aFieldChangeKeepsOldAndNewValues() {
        service.recordChange(task, TaskHistoryField.PRIORITY, "LOW", "HIGH", user);

        TaskHistoryEntry entry = saved();
        assertEquals(TaskHistoryType.FIELD_CHANGED, entry.getType());
        assertEquals(TaskHistoryField.PRIORITY, entry.getField());
        assertEquals("LOW", entry.getOldValue());
        assertEquals("HIGH", entry.getNewValue());
    }

    @Test
    void settingAFieldFromOrToNothingIsAChange() {
        service.recordChange(task, TaskHistoryField.ASSIGNEE, null, "Grace Hopper", user);

        assertNull(saved().getOldValue());
    }

    @Test
    void anUnchangedFieldWritesNothing() {
        service.recordChange(task, TaskHistoryField.TITLE, "Fix login", "Fix login", user);
        service.recordChange(task, TaskHistoryField.ESTIMATE, null, null, user);

        verifyNoInteractions(historyRepository);
    }

    @Test
    void aDescriptionChangeIsRecordedWithoutItsText() {
        service.recordChange(task, TaskHistoryField.DESCRIPTION, "{\"type\":\"doc\"}", "{\"type\":\"doc\",\"x\":1}", user);

        TaskHistoryEntry entry = saved();
        assertEquals(TaskHistoryField.DESCRIPTION, entry.getField());
        assertNull(entry.getOldValue());
        assertNull(entry.getNewValue());
    }

    @Test
    void recordChangesWritesOneEntryPerDifferingFieldInAFixedOrder() {
        TaskSnapshot before = new TaskSnapshot("Fix login", "BUG", "LOW", null, "3", null, "backend", "old text");
        TaskSnapshot after = new TaskSnapshot("Fix login", "BUG", "HIGH", "Grace Hopper", "3", null, "backend, ui", "new text");

        service.recordChanges(task, before, after, user);

        ArgumentCaptor<TaskHistoryEntry> entries = ArgumentCaptor.forClass(TaskHistoryEntry.class);
        verify(historyRepository, org.mockito.Mockito.times(4)).save(entries.capture());
        assertEquals(List.of(TaskHistoryField.PRIORITY, TaskHistoryField.ASSIGNEE, TaskHistoryField.LABELS,
                        TaskHistoryField.DESCRIPTION),
                entries.getAllValues().stream().map(TaskHistoryEntry::getField).toList());
        TaskHistoryEntry priority = entries.getAllValues().get(0);
        assertEquals("LOW", priority.getOldValue());
        assertEquals("HIGH", priority.getNewValue());
        assertNull(entries.getAllValues().get(3).getNewValue(), "description text is never stored");
    }

    @Test
    void recordChangesWithEqualSnapshotsWritesNothing() {
        TaskSnapshot same = new TaskSnapshot("Fix login", "BUG", "LOW", null, "3", null, "backend", "text");

        service.recordChanges(task, same, same, user);

        verifyNoInteractions(historyRepository);
    }

    @Test
    void aDeletedCommentIsRecordedByIdWithoutText() {
        service.recordCommentDeleted(task, 77L, user);

        TaskHistoryEntry entry = saved();
        assertEquals(TaskHistoryType.COMMENT_DELETED, entry.getType());
        assertEquals(77L, entry.getRefId());
        assertNull(entry.getOldValue());
        assertNull(entry.getNewValue());
    }

    @Test
    void subtasksAddedAndRemovedAreRecordedOnTheParentWithKeyAndTitle() {
        Task subtask = Task.builder().id(57L).key("MOB-57").title("Write tests").build();

        service.recordSubtaskAdded(task, subtask, user);
        service.recordSubtaskRemoved(task, subtask, user);

        ArgumentCaptor<TaskHistoryEntry> entries = ArgumentCaptor.forClass(TaskHistoryEntry.class);
        verify(historyRepository, org.mockito.Mockito.times(2)).save(entries.capture());
        TaskHistoryEntry added = entries.getAllValues().get(0);
        TaskHistoryEntry removed = entries.getAllValues().get(1);
        assertEquals(TaskHistoryType.SUBTASK_ADDED, added.getType());
        assertEquals(task, added.getTask());
        assertEquals("MOB-57 Write tests", added.getNewValue());
        assertEquals(57L, added.getRefId());
        assertEquals(TaskHistoryType.SUBTASK_REMOVED, removed.getType());
        assertEquals("MOB-57 Write tests", removed.getOldValue());
        assertNull(removed.getNewValue());
    }

    @Test
    void aRestoreIsRecorded() {
        service.recordRestored(task, user);

        assertEquals(TaskHistoryType.RESTORED, saved().getType());
    }

    // --- reading ---

    @Test
    void listReturnsOnePageInTheRequestedOrder() {
        when(access.require(PROJECT_ID, user, ProjectPermission.VIEW_WORK))
                .thenReturn(new ProjectAccessResult(null, null, null, Set.of(ProjectPermission.VIEW_WORK)));
        when(taskRepository.findByIdAndProjectId(5L, PROJECT_ID)).thenReturn(Optional.of(task));
        TaskHistoryEntry entry = TaskHistoryEntry.builder().id(9L).task(task).type(TaskHistoryType.CREATED)
                .actorId(42L).actorName("Ada Lovelace").build();
        PageRequest newest = PageRequest.of(1, 20, TaskHistoryOrder.NEWEST.sort());
        when(historyRepository.findByTaskId(5L, newest)).thenReturn(new PageImpl<>(List.of(entry), newest, 21));

        PageResponse<TaskHistoryEntryResponse> response = service.list(user, PROJECT_ID, 5L,
                TaskHistoryOrder.NEWEST, PageRequest.of(1, 20));

        assertEquals(21, response.totalElements());
        TaskHistoryEntryResponse first = response.content().get(0);
        assertEquals(TaskHistoryType.CREATED, first.type());
        assertEquals(new TaskHistoryEntryResponse.Actor(42L, "Ada Lovelace"), first.actor());

        PageRequest oldest = PageRequest.of(0, 20, TaskHistoryOrder.OLDEST.sort());
        when(historyRepository.findByTaskId(5L, oldest)).thenReturn(new PageImpl<>(List.of(entry), oldest, 1));
        assertEquals(1, service.list(user, PROJECT_ID, 5L, TaskHistoryOrder.OLDEST,
                PageRequest.of(0, 20, Sort.by("actorName"))).totalElements(), "a stray sort is replaced");
        verify(historyRepository).findByTaskId(5L, oldest);
    }

    @Test
    void listOfAMissingOrDeletedTaskIs404() {
        when(access.require(PROJECT_ID, user, ProjectPermission.VIEW_WORK))
                .thenReturn(new ProjectAccessResult(null, null, null, Set.of(ProjectPermission.VIEW_WORK)));
        when(taskRepository.findByIdAndProjectId(5L, PROJECT_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.list(user, PROJECT_ID, 5L, TaskHistoryOrder.NEWEST, PageRequest.of(0, 20)));
        verifyNoInteractions(historyRepository);
    }

    @Test
    void listNeedsViewWork() {
        when(access.require(PROJECT_ID, user, ProjectPermission.VIEW_WORK))
                .thenThrow(new AccessDeniedException("Project permission required: VIEW_WORK"));

        assertThrows(AccessDeniedException.class,
                () -> service.list(user, PROJECT_ID, 5L, TaskHistoryOrder.NEWEST, PageRequest.of(0, 20)));
        verifyNoInteractions(historyRepository, taskRepository);
    }

    @Test
    void entriesAreNeverSavedForAnotherReason() {
        // Guard for the append-only rule: the service only ever calls save().
        service.recordCreated(task, user);
        verify(historyRepository).save(any(TaskHistoryEntry.class));
        org.mockito.Mockito.verifyNoMoreInteractions(historyRepository);
    }
}