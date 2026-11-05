package team.sprintory_backend.notification.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.notification.models.TaskWatcherId;
import team.sprintory_backend.notification.repositories.TaskWatcherRepository;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.project.services.ProjectAccessResult;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.user.models.User;

class TaskWatcherServiceTest {

    private static final Long PROJECT_ID = 3L;
    private static final Long TASK_ID = 500L;

    private ProjectAccess access;
    private TaskRepository taskRepository;
    private TaskWatcherRepository watcherRepository;
    private TaskWatcherService service;
    private User user;
    private Task task;

    @BeforeEach
    void setUp() {
        access = mock(ProjectAccess.class);
        taskRepository = mock(TaskRepository.class);
        watcherRepository = mock(TaskWatcherRepository.class);
        service = new TaskWatcherService(access, taskRepository, watcherRepository);
        user = User.builder().id(42L).firstName("Ada").lastName("Lovelace").email("ada@example.com").build();
        Project project = Project.builder().id(PROJECT_ID).name("Mobile App").key("MOB").build();
        task = Task.builder().id(TASK_ID).project(project).key("MOB-1").title("Fix login").build();
    }

    private void givenVisibleTask() {
        when(access.require(PROJECT_ID, user, ProjectPermission.VIEW_WORK))
                .thenReturn(new ProjectAccessResult(null, null, null, Set.of(ProjectPermission.VIEW_WORK)));
        when(taskRepository.findByIdAndProjectId(TASK_ID, PROJECT_ID)).thenReturn(Optional.of(task));
    }

    @Test
    void isWatchingReportsTheRow() {
        givenVisibleTask();
        when(watcherRepository.existsById(new TaskWatcherId(TASK_ID, 42L))).thenReturn(true);

        assertTrue(service.isWatching(user, PROJECT_ID, TASK_ID).watching());
    }

    @Test
    void isWatchingIsFalseWithoutARow() {
        givenVisibleTask();

        assertFalse(service.isWatching(user, PROJECT_ID, TASK_ID).watching());
    }

    @Test
    void watchInsertsUnlessPresent() {
        givenVisibleTask();

        assertTrue(service.watch(user, PROJECT_ID, TASK_ID).watching());
        verify(watcherRepository).insertIfAbsent(TASK_ID, 42L);
    }

    @Test
    void unwatchDeletesTheRow() {
        givenVisibleTask();

        assertFalse(service.unwatch(user, PROJECT_ID, TASK_ID).watching());
        verify(watcherRepository).deleteById(new TaskWatcherId(TASK_ID, 42L));
    }

    @Test
    void aTaskOutsideTheProjectOrDeletedIs404AndNothingIsWritten() {
        when(access.require(PROJECT_ID, user, ProjectPermission.VIEW_WORK))
                .thenReturn(new ProjectAccessResult(null, null, null, Set.of(ProjectPermission.VIEW_WORK)));
        when(taskRepository.findByIdAndProjectId(TASK_ID, PROJECT_ID)).thenReturn(Optional.empty());

        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class,
                () -> service.watch(user, PROJECT_ID, TASK_ID));
        assertEquals("Task not found.", e.getMessage());
        verifyNoInteractions(watcherRepository);
    }

    @Test
    void anInvisibleProjectIs404AndNothingIsWritten() {
        when(access.require(PROJECT_ID, user, ProjectPermission.VIEW_WORK))
                .thenThrow(new ResourceNotFoundException("Project not found."));

        assertThrows(ResourceNotFoundException.class, () -> service.unwatch(user, PROJECT_ID, TASK_ID));
        verifyNoInteractions(taskRepository, watcherRepository);
    }

    @Test
    void withoutViewWorkItIs403AndNothingIsWritten() {
        // A workspace admin outside the project sees it, but has no VIEW_WORK.
        when(access.require(PROJECT_ID, user, ProjectPermission.VIEW_WORK))
                .thenThrow(new AccessDeniedException("Project permission required: VIEW_WORK"));

        assertThrows(AccessDeniedException.class, () -> service.watch(user, PROJECT_ID, TASK_ID));
        verifyNoInteractions(taskRepository, watcherRepository);
    }

    @Test
    void watchingNeedsOnlyViewWorkNotAnActiveWorkspace() {
        givenVisibleTask();

        service.watch(user, PROJECT_ID, TASK_ID);

        // require, not requireActive: an archived workspace still allows watching.
        verify(access).require(PROJECT_ID, user, ProjectPermission.VIEW_WORK);
    }

    @Test
    void autoWatchInsertsWithoutAnAccessCheck() {
        service.autoWatch(task, user);

        verify(watcherRepository).insertIfAbsent(TASK_ID, 42L);
        verifyNoInteractions(access);
    }

    @Test
    void watcherIdsComeFromTheRepository() {
        when(watcherRepository.findUserIdsByTaskId(TASK_ID)).thenReturn(List.of(42L, 43L));

        assertEquals(List.of(42L, 43L), service.watcherIdsOf(TASK_ID));
    }
}