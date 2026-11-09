package team.sprintory_backend.sprint.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import team.sprintory_backend.notification.events.SprintCompleted;
import team.sprintory_backend.notification.events.SprintStarted;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.project.services.ProjectAccessResult;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.sprint.dto.*;
import team.sprintory_backend.sprint.exceptions.SprintConflictException;
import team.sprintory_backend.sprint.models.*;
import team.sprintory_backend.sprint.repositories.*;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.exceptions.WorkspaceArchivedException;
import team.sprintory_backend.project.services.ProjectService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Protects lock-before-load behavior and event publication from regression. */
class SprintServiceTest {
    private SprintRepository repository;
    private SprintAvailabilityRepository availability;
    private SprintCommitmentRepository commitments;
    private SprintScopeChangeRepository changes;
    private ProjectAccess access;
    private ProjectRepository projects;
    private ProjectMemberRepository members;
    private UserRepository users;
    private TaskRepository tasks;
    private ApplicationEventPublisher events;
    private SprintService service;
    private Project project;
    private Sprint sprint;
    private User actor;

    @BeforeEach void setUp() {
        repository = mock(SprintRepository.class);
        availability = mock(SprintAvailabilityRepository.class);
        commitments = mock(SprintCommitmentRepository.class);
        changes = mock(SprintScopeChangeRepository.class);
        access = mock(ProjectAccess.class);
        projects = mock(ProjectRepository.class);
        members = mock(ProjectMemberRepository.class);
        users = mock(UserRepository.class);
        tasks = mock(TaskRepository.class);
        events = mock(ApplicationEventPublisher.class);
        project = Project.builder().id(10L).name("Scrum").key("SPR").template(ProjectTemplate.SCRUM).build();
        sprint = new Sprint(project, 1, "SPR Sprint 1", "Goal", 15,
                LocalDate.now(), LocalDate.now().plusDays(13));
        sprint.setId(20L);
        actor = User.builder().id(2L).firstName("Sam").lastName("Master").email("sam@test.invalid").build();
        service = new SprintService(repository, availability, commitments, changes, access, projects,
                members, users, tasks, events, mock(ProjectService.class), mock(SprintScopeHistoryWriter.class));
    }

    private void writable() {
        when(repository.findProjectId(20L)).thenReturn(Optional.of(10L));
        when(projects.lockById(10L)).thenReturn(Optional.of(project));
        when(repository.findById(20L)).thenReturn(Optional.of(sprint));
        when(access.requireActive(10L, actor, ProjectPermission.MANAGE_SPRINTS))
                .thenReturn(new ProjectAccessResult(project, null, null, Set.of(ProjectPermission.MANAGE_SPRINTS)));
    }

    @Test void completeLoadsSprintOnlyAfterProjectLockAndCannotBeRepeated() {
        writable();
        sprint.setStatus(SprintStatus.ACTIVE);
        when(tasks.findBySprintIdAndParentIsNullOrderBySprintPositionAscIdAsc(20L)).thenReturn(List.of());
        when(repository.saveAndFlush(any(Sprint.class))).thenAnswer(inv -> inv.getArgument(0));
        service.complete(actor, 20L, null);
        var order = inOrder(repository, projects);
        order.verify(repository).findProjectId(20L);
        order.verify(projects).lockById(10L);
        order.verify(repository).findById(20L);
        verify(events).publishEvent(isA(SprintCompleted.class));
        assertThrows(SprintConflictException.class, () -> service.complete(actor, 20L, null));
        verify(events, times(1)).publishEvent(isA(SprintCompleted.class));
    }

    @Test void deletingPlannedSprintUnlinksEvenDeletedTasks() {
        writable();
        service.delete(actor, 20L);
        verify(tasks).clearSprintAssignmentsIncludingDeleted(20L);
        verify(repository).delete(sprint);
    }

    @Test void startPublishesSprintStartedAfterSnapshot() {
        writable();
        Task item = mock(Task.class);
        when(item.getId()).thenReturn(33L);
        when(item.getKey()).thenReturn("SPR-1");
        when(item.getEstimate()).thenReturn(8);
        when(tasks.findBySprintIdAndParentIsNullOrderBySprintPositionAscIdAsc(20L)).thenReturn(List.of(item));
        when(repository.saveAndFlush(any(Sprint.class))).thenAnswer(inv -> inv.getArgument(0));
        var result = service.start(actor, 20L, null);
        assertEquals(SprintStatus.ACTIVE, result.status());
        assertEquals(8, result.committedPoints());
        verify(commitments).saveAll(any());
        verify(events).publishEvent(isA(SprintStarted.class));
    }

    @Test void archivedWorkspacePreventsMutation() {
        when(repository.findProjectId(20L)).thenReturn(Optional.of(10L));
        when(access.requireActive(10L, actor, ProjectPermission.MANAGE_SPRINTS))
                .thenThrow(new WorkspaceArchivedException("Workspace is archived."));
        assertThrows(WorkspaceArchivedException.class, () -> service.delete(actor, 20L));
        verify(repository, never()).findById(20L);
    }
}
