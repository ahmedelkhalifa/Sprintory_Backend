package team.sprintory_backend.sprint.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import team.sprintory_backend.notification.events.SprintCompleted;
import team.sprintory_backend.notification.events.SprintStarted;
import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectBoardColumn;
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
import team.sprintory_backend.task.services.TaskStatusHistoryService;
import team.sprintory_backend.audit.models.AuditAction;
import team.sprintory_backend.audit.models.AuditChange;
import team.sprintory_backend.audit.services.AuditLogService;
import team.sprintory_backend.sprint.exceptions.EarlyCloseConfirmationRequiredException;
import org.springframework.security.access.AccessDeniedException;
import org.mockito.ArgumentCaptor;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.exceptions.WorkspaceArchivedException;
import team.sprintory_backend.project.services.ProjectService;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
    private SprintOutcomeRepository outcomes;
    private TaskStatusHistoryService taskHistory;
    private AuditLogService audit;
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
        outcomes = mock(SprintOutcomeRepository.class);
        taskHistory = mock(TaskStatusHistoryService.class);
        audit = mock(AuditLogService.class);
        project = Project.builder().id(10L).name("Scrum").key("SPR").template(ProjectTemplate.SCRUM).build();
        sprint = new Sprint(project, 1, "SPR Sprint 1", "Goal", 15,
                LocalDate.now(), LocalDate.now().plusDays(13));
        sprint.setId(20L);
        actor = User.builder().id(2L).firstName("Sam").lastName("Master").email("sam@test.invalid").build();
        service = new SprintService(repository, availability, commitments, changes, access, projects,
                members, users, tasks, events, mock(ProjectService.class), mock(SprintScopeHistoryWriter.class),
                outcomes, taskHistory, audit);
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
        service.complete(actor, 20L, closing(null, false));
        var order = inOrder(repository, projects);
        order.verify(repository).findProjectId(20L);
        order.verify(projects).lockById(10L);
        order.verify(repository).findById(20L);
        verify(events).publishEvent(isA(SprintCompleted.class));
        assertThrows(SprintConflictException.class, () -> service.complete(actor, 20L, closing(null, false)));
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

    // --- PMP-41: ended sprints are read-only, and free their remaining dates ---

    private static Instant at(LocalDate day) {
        return day.atTime(10, 0).toInstant(ZoneOffset.UTC);
    }

    private Sprint ended(SprintStatus status, LocalDate start, LocalDate end, LocalDate endedOn) {
        Sprint s = new Sprint(project, 9, "SPR Sprint 9", "Goal", null, start, end);
        s.setId(90L);
        s.setStatus(status);
        s.setCompletedAt(at(endedOn));
        return s;
    }

    @Test void occupiedUntilIsTheExclusiveEndOfTheDatesASprintHolds() {
        LocalDate start = LocalDate.of(2026, 11, 2);
        LocalDate end = LocalDate.of(2026, 11, 15);
        Sprint planned = new Sprint(project, 1, "A", "", null, start, end);
        assertEquals(end.plusDays(1), SprintService.occupiedUntil(planned), "planned: through endDate");
        assertEquals(LocalDate.of(2026, 11, 9),
                SprintService.occupiedUntil(ended(SprintStatus.COMPLETED, start, end, LocalDate.of(2026, 11, 9))),
                "closed early: the close day is free");
        assertEquals(end, SprintService.occupiedUntil(ended(SprintStatus.CANCELLED, start, end, end)),
                "ended on its last planned day: that day is free too");
        assertEquals(end.plusDays(1),
                SprintService.occupiedUntil(ended(SprintStatus.COMPLETED, start, end, end.plusDays(3))),
                "closed late: never more than its planned dates");
        assertEquals(start, SprintService.occupiedUntil(ended(SprintStatus.CANCELLED, start, end, start)),
                "ended on its start day: occupies nothing");
        assertNull(SprintService.occupiedUntil(new Sprint(project, 2, "B", "", null, null, null)));
    }

    @Test void aSprintCanStartOnTheDayThePreviousOneEnded() {
        writable();
        LocalDate closedOn = LocalDate.now(ZoneOffset.UTC);
        Sprint previous = ended(SprintStatus.COMPLETED, closedOn.minusDays(5), closedOn.plusDays(8), closedOn);
        when(repository.findByProjectIdOrderByNumberAsc(10L)).thenReturn(List.of(previous));
        when(repository.saveAndFlush(any(Sprint.class))).thenAnswer(inv -> inv.getArgument(0));

        SprintResponse explicit = service.create(actor, 10L,
                new SprintRequest("Next", "Goal", null, closedOn, closedOn.plusDays(13)));
        assertEquals(closedOn, explicit.startDate());

        SprintResponse defaults = service.create(actor, 10L, new SprintRequest(null, "Goal", null, null, null));
        assertEquals(closedOn, defaults.startDate(), "default dates start on the close day as well");
    }

    @Test void datesStillCannotOverlapTheActiveSprint() {
        writable();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Sprint running = new Sprint(project, 5, "Running", "Goal", null, today.minusDays(3), today.plusDays(10));
        running.setId(50L);
        running.setStatus(SprintStatus.ACTIVE);
        when(repository.findByProjectIdOrderByNumberAsc(10L)).thenReturn(List.of(running));

        assertThrows(BadRequestException.class, () -> service.create(actor, 10L,
                new SprintRequest("Next", "Goal", null, today.plusDays(10), today.plusDays(20))),
                "the active sprint's last day is still taken");
    }

    @Test void aCancelledSprintRefusesEditsAvailabilityAndWork() {
        writable();
        sprint.setStatus(SprintStatus.CANCELLED);
        assertThrows(SprintConflictException.class, () -> service.update(actor, 20L, new SprintRequest(
                "Renamed", null, null, null, null)));
        assertThrows(SprintConflictException.class, () -> service.setAvailability(actor, 20L, 2L,
                new AvailabilityRequest(1)));

        Task backlogItem = Task.builder().id(33L).project(project).key("SPR-3").build();
        when(tasks.findByIdAndProjectId(33L, 10L)).thenReturn(Optional.of(backlogItem));
        assertThrows(SprintConflictException.class, () -> service.move(actor, 10L,
                new MoveSprintTaskRequest(33L, 20L, null)), "a cancelled sprint can't accept work");

        Task cancelledItem = Task.builder().id(34L).project(project).key("SPR-4").sprint(sprint).build();
        when(tasks.findByIdAndProjectId(34L, 10L)).thenReturn(Optional.of(cancelledItem));
        assertThrows(SprintConflictException.class, () -> service.move(actor, 10L,
                new MoveSprintTaskRequest(34L, null, null)), "its items can't be moved out");
        verify(events, never()).publishEvent(any());
    }

    @Test void theSprintListShowsOnlyPlannedAndActiveSprints() {
        when(access.require(10L, actor, ProjectPermission.VIEW_WORK))
                .thenReturn(new ProjectAccessResult(project, null, null, Set.of(ProjectPermission.VIEW_WORK)));
        service.list(actor, 10L);
        verify(repository).findByProjectIdAndStatusInOrderByNumberAsc(10L,
                List.of(SprintStatus.PLANNED, SprintStatus.ACTIVE));
    }

    @Test void velocityReadsCompletedSprintsOnlySoCancelledOnesAreExcluded() {
        when(repository.findById(20L)).thenReturn(Optional.of(sprint));
        when(access.require(10L, actor, ProjectPermission.VIEW_WORK))
                .thenReturn(new ProjectAccessResult(project, null, null, Set.of(ProjectPermission.VIEW_WORK)));
        service.suggestion(actor, 20L);
        verify(repository).findTop3ByProjectIdAndStatusOrderByCompletedAtDescIdDesc(10L, SprintStatus.COMPLETED);
        verify(repository, never()).findTop3ByProjectIdAndStatusOrderByCompletedAtDescIdDesc(10L, SprintStatus.CANCELLED);
    }

    // --- PMP-41: close preview ---

    private void readable() {
        when(repository.findById(20L)).thenReturn(Optional.of(sprint));
        when(access.require(10L, actor, ProjectPermission.VIEW_WORK))
                .thenReturn(new ProjectAccessResult(project, null, null, Set.of(ProjectPermission.VIEW_WORK)));
    }

    private static ProjectBoardColumn column(String name, BoardColumnCategory category) {
        ProjectBoardColumn column = new ProjectBoardColumn(null, name, 0);
        column.setCategory(category);
        return column;
    }

    private Task item(long id, Integer estimate, ProjectBoardColumn status) {
        return Task.builder().id(id).project(project).key("SPR-" + id).title("Item " + id)
                .estimate(estimate).status(status).sprint(sprint).build();
    }

    @Test void closePreviewShowsDoneAndUnfinishedWithoutPartialCredit() {
        readable();
        sprint.setStatus(SprintStatus.ACTIVE);
        sprint.setEndDate(LocalDate.now(ZoneOffset.UTC).plusDays(13));
        ProjectBoardColumn todo = column("To Do", BoardColumnCategory.TODO);
        ProjectBoardColumn doing = column("In Progress", BoardColumnCategory.IN_PROGRESS);
        ProjectBoardColumn done = column("Done", BoardColumnCategory.DONE);
        Task story = item(3, 5, doing); // 3 of its 4 subtasks are done: still unfinished, 0 points
        when(tasks.findBySprintIdAndParentIsNullOrderBySprintPositionAscIdAsc(20L)).thenReturn(List.of(
                item(1, 8, done), story, item(2, 12, done), item(4, 1, todo), item(5, null, todo)));
        when(tasks.findSubtaskProgress(List.of(3L, 4L, 5L))).thenReturn(List.<Object[]>of(new Object[] {3L, 4L, 3L}));
        Sprint planned = new Sprint(project, 2, "SPR Sprint 2", "", null, null, null);
        planned.setId(21L);
        when(repository.findByProjectIdAndStatusInOrderByNumberAsc(10L, List.of(SprintStatus.PLANNED)))
                .thenReturn(List.of(planned));

        CloseSprintPreviewResponse preview = service.closePreview(actor, 20L);

        assertEquals(2, preview.doneCount());
        assertEquals(20, preview.donePoints(), "20 done");
        assertEquals(3, preview.unfinishedCount());
        assertEquals(6, preview.unfinishedPoints(), "6 unfinished");
        assertEquals(List.of(3L, 4L, 5L), preview.unfinished().stream()
                .map(CloseSprintPreviewResponse.UnfinishedItem::taskId).toList(), "sprint order");
        CloseSprintPreviewResponse.UnfinishedItem first = preview.unfinished().get(0);
        assertEquals("SPR-3", first.key());
        assertEquals("In Progress", first.statusName());
        assertEquals(1, first.openSubtasks());
        assertEquals(0, preview.unfinished().get(1).openSubtasks(), "no subtasks");
        assertTrue(preview.endsEarly());
        assertEquals(13, preview.daysLeft());
        assertEquals(List.of(21L), preview.plannedSprints().stream()
                .map(CloseSprintPreviewResponse.PlannedSprint::id).toList());
    }

    @Test void closePreviewOnTheLastDayIsNotEarly() {
        readable();
        sprint.setStatus(SprintStatus.ACTIVE);
        sprint.setEndDate(LocalDate.now(ZoneOffset.UTC));
        CloseSprintPreviewResponse preview = service.closePreview(actor, 20L);
        assertFalse(preview.endsEarly());
        assertEquals(0, preview.daysLeft());
        assertEquals(0, preview.unfinishedCount());
        verify(tasks, never()).findSubtaskProgress(any());
    }

    @Test void closePreviewNeedsAnActiveSprint() {
        readable();
        for (SprintStatus status : List.of(SprintStatus.PLANNED, SprintStatus.COMPLETED, SprintStatus.CANCELLED)) {
            sprint.setStatus(status);
            assertThrows(SprintConflictException.class, () -> service.closePreview(actor, 20L), status.name());
        }
    }

    // --- PMP-41: close ---

    private static CompleteSprintRequest closing(Long carryOverSprintId, boolean createSprint) {
        return new CompleteSprintRequest(carryOverSprintId, createSprint, SprintGoalAchieved.YES, null, true);
    }

    private void nothingWritten() {
        assertEquals(SprintStatus.ACTIVE, sprint.getStatus());
        verify(outcomes, never()).saveAll(any());
        verify(taskHistory, never()).recordCarriedOver(any(), any(), any(), any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(AuditChange[].class));
        verify(events, never()).publishEvent(any());
    }

    @Test void closingNeedsTheGoalAnswer() {
        writable();
        sprint.setStatus(SprintStatus.ACTIVE);
        assertThrows(BadRequestException.class, () -> service.complete(actor, 20L, null));
        assertThrows(BadRequestException.class, () -> service.complete(actor, 20L,
                new CompleteSprintRequest(null, false, null, "note", true)));
        nothingWritten();
    }

    @Test void closingBeforeTheEndDateNeedsConfirmation() {
        writable();
        sprint.setStatus(SprintStatus.ACTIVE);
        sprint.setEndDate(LocalDate.now(ZoneOffset.UTC).plusDays(1));
        assertThrows(EarlyCloseConfirmationRequiredException.class, () -> service.complete(actor, 20L,
                new CompleteSprintRequest(null, false, SprintGoalAchieved.NO, null, false)));
        nothingWritten();

        when(repository.saveAndFlush(any(Sprint.class))).thenAnswer(inv -> inv.getArgument(0));
        service.complete(actor, 20L, new CompleteSprintRequest(null, false, SprintGoalAchieved.NO, null, true));
        assertEquals(SprintStatus.COMPLETED, sprint.getStatus());
    }

    @Test void closingOnTheLastDayNeedsNoConfirmation() {
        writable();
        sprint.setStatus(SprintStatus.ACTIVE);
        sprint.setEndDate(LocalDate.now(ZoneOffset.UTC));
        when(repository.saveAndFlush(any(Sprint.class))).thenAnswer(inv -> inv.getArgument(0));
        service.complete(actor, 20L, new CompleteSprintRequest(null, false, SprintGoalAchieved.YES, null, false));
        assertEquals(SprintStatus.COMPLETED, sprint.getStatus());
    }

    @Test void onlyTheScrumMasterCloses() {
        when(repository.findProjectId(20L)).thenReturn(Optional.of(10L));
        when(access.requireActive(10L, actor, ProjectPermission.MANAGE_SPRINTS))
                .thenThrow(new AccessDeniedException("Sprint permission required."));
        sprint.setStatus(SprintStatus.ACTIVE);
        assertThrows(AccessDeniedException.class, () -> service.complete(actor, 20L, closing(null, false)));
        verify(projects, never()).lockById(any());
        nothingWritten();
    }

    @SuppressWarnings("unchecked")
    @Test void closeSendsUnfinishedWorkToTheTopOfTheBacklogAndFreezesTheResult() {
        writable();
        sprint.setStatus(SprintStatus.ACTIVE);
        project.setWorkspace(new team.sprintory_backend.workspace.models.Workspace());
        ProjectBoardColumn doing = column("In Progress", BoardColumnCategory.IN_PROGRESS);
        ProjectBoardColumn done = column("Done", BoardColumnCategory.DONE);
        Task first = item(1, 3, doing);
        Task finished = item(2, 8, done);
        Task second = item(3, 2, doing);
        second.setCarryOverCount(1);
        when(tasks.findBySprintIdAndParentIsNullOrderBySprintPositionAscIdAsc(20L))
                .thenReturn(List.of(first, finished, second));
        when(tasks.findMinBacklogRankOfBacklog(10L)).thenReturn(5_000_000L);
        when(repository.saveAndFlush(any(Sprint.class))).thenAnswer(inv -> inv.getArgument(0));

        CompleteSprintResponse result = service.complete(actor, 20L,
                new CompleteSprintRequest(null, false, SprintGoalAchieved.PARTIALLY, "  Ran out of time  ", true));

        assertEquals(2, result.carriedOver());
        assertNull(result.toSprint());
        assertEquals(SprintStatus.COMPLETED, sprint.getStatus());
        assertNotNull(sprint.getCompletedAt());
        assertEquals(8, sprint.getCompletedPoints(), "no partial credit");
        assertEquals(5, sprint.getUnfinishedPoints());
        assertEquals(SprintGoalAchieved.PARTIALLY, sprint.getGoalAchieved());
        assertEquals("Ran out of time", sprint.getOutcomeNote());
        // Top of the backlog, in sprint order, above the current first item (5,000,000).
        assertNull(first.getSprint());
        assertNull(second.getSprint());
        assertEquals(3_000_000L, first.getBacklogRank());
        assertEquals(4_000_000L, second.getBacklogRank());
        assertSame(sprint, finished.getSprint(), "done work stays linked");
        assertEquals(1, first.getCarryOverCount());
        assertEquals(2, second.getCarryOverCount(), "Carried over ×2");
        assertEquals(0, finished.getCarryOverCount());
        verify(taskHistory).recordCarriedOver(first, "SPR Sprint 1", null, actor, 20L);
        verify(taskHistory).recordCarriedOver(second, "SPR Sprint 1", null, actor, 20L);
        verify(taskHistory, never()).recordCarriedOver(eq(finished), any(), any(), any(), any());

        ArgumentCaptor<List<SprintOutcome>> rows = ArgumentCaptor.forClass(List.class);
        verify(outcomes).saveAll(rows.capture());
        assertEquals(List.of(SprintOutcome.Result.CARRIED_OVER, SprintOutcome.Result.DONE, SprintOutcome.Result.CARRIED_OVER),
                rows.getValue().stream().map(SprintOutcome::getOutcome).toList());
        assertEquals(List.of(3, 8, 2), rows.getValue().stream().map(SprintOutcome::getEstimate).toList());

        verify(audit).record(same(project.getWorkspace()), same(actor), eq(AuditAction.SPRINT_COMPLETED),
                eq(team.sprintory_backend.audit.models.AuditTarget.sprint(sprint)), same(project),
                eq(AuditChange.of("status", "ACTIVE", "COMPLETED")),
                eq(AuditChange.of("completedPoints", null, "8")),
                eq(AuditChange.of("unfinishedPoints", null, "5")),
                eq(AuditChange.of("goalAchieved", null, "PARTIALLY")),
                eq(AuditChange.of("carriedOver", null, "2 items to the backlog")));
        verify(events).publishEvent(isA(SprintCompleted.class));
    }

    @SuppressWarnings("unchecked")
    @Test void closeCanMoveUnfinishedWorkToTheFrontOfAPlannedSprint() {
        writable();
        sprint.setStatus(SprintStatus.ACTIVE);
        project.setWorkspace(new team.sprintory_backend.workspace.models.Workspace());
        Sprint next = new Sprint(project, 2, "SPR Sprint 2", "", null, null, null);
        next.setId(21L);
        when(repository.findById(21L)).thenReturn(Optional.of(next));
        ProjectBoardColumn doing = column("In Progress", BoardColumnCategory.IN_PROGRESS);
        Task carried = item(1, 3, doing);
        carried.setBacklogRank(7L);
        Task planned = Task.builder().id(9L).project(project).key("SPR-9").status(doing).sprint(next).sprintPosition(1L).build();
        when(tasks.findBySprintIdAndParentIsNullOrderBySprintPositionAscIdAsc(20L)).thenReturn(List.of(carried));
        when(tasks.findBySprintIdAndParentIsNullOrderBySprintPositionAscIdAsc(21L)).thenReturn(List.of(planned));
        when(repository.saveAndFlush(any(Sprint.class))).thenAnswer(inv -> inv.getArgument(0));

        CompleteSprintResponse result = service.complete(actor, 20L, closing(21L, false));

        assertEquals(21L, result.toSprint().id());
        assertSame(next, carried.getSprint());
        assertEquals(1L, carried.getSprintPosition(), "carried-over work goes first");
        assertEquals(2L, planned.getSprintPosition());
        assertEquals(7L, carried.getBacklogRank(), "the backlog rank is untouched");
        verify(tasks, never()).findMinBacklogRankOfBacklog(any());
        verify(taskHistory).recordCarriedOver(carried, "SPR Sprint 1", "SPR Sprint 2", actor, 20L);
        ArgumentCaptor<List<SprintOutcome>> rows = ArgumentCaptor.forClass(List.class);
        verify(outcomes).saveAll(rows.capture());
        assertEquals(21L, rows.getValue().get(0).getDestinationSprintId());
    }

    // --- PMP-41: cancel ---

    private void cancellable() {
        when(repository.findProjectId(20L)).thenReturn(Optional.of(10L));
        when(projects.lockById(10L)).thenReturn(Optional.of(project));
        when(repository.findById(20L)).thenReturn(Optional.of(sprint));
        when(access.requireActive(10L, actor, ProjectPermission.CANCEL_SPRINTS))
                .thenReturn(new ProjectAccessResult(project, null, null, Set.of(ProjectPermission.CANCEL_SPRINTS)));
    }

    @Test void cancelSendsUnfinishedWorkToTheBacklogAndRecordsTheReason() {
        cancellable();
        sprint.setStatus(SprintStatus.ACTIVE);
        project.setWorkspace(new team.sprintory_backend.workspace.models.Workspace());
        Task open = item(1, 3, column("In Progress", BoardColumnCategory.IN_PROGRESS));
        Task finished = item(2, 12, column("Done", BoardColumnCategory.DONE));
        when(tasks.findBySprintIdAndParentIsNullOrderBySprintPositionAscIdAsc(20L)).thenReturn(List.of(open, finished));
        when(tasks.findMinBacklogRankOfBacklog(10L)).thenReturn(null);
        when(repository.saveAndFlush(any(Sprint.class))).thenAnswer(inv -> inv.getArgument(0));

        CompleteSprintResponse result = service.cancel(actor, 20L, new CancelSprintRequest("  Goal became obsolete "));

        assertEquals(SprintStatus.CANCELLED, sprint.getStatus());
        assertNotNull(sprint.getCompletedAt());
        assertEquals("Goal became obsolete", sprint.getCancelReason());
        assertEquals(12, sprint.getCompletedPoints(), "done items are still counted for reports");
        assertEquals(3, sprint.getUnfinishedPoints());
        assertNull(sprint.getGoalAchieved(), "no goal answer on a cancel");
        assertEquals(1, result.carriedOver());
        assertNull(result.toSprint());
        assertNull(open.getSprint());
        assertEquals(-1_000_000L, open.getBacklogRank(), "top of an empty backlog");
        assertSame(sprint, finished.getSprint(), "done items stay linked");
        assertEquals(1, open.getCarryOverCount());
        verify(taskHistory).recordCarriedOver(open, "SPR Sprint 1", null, actor, 20L);
        verify(outcomes).saveAll(any());
        verify(audit).record(same(project.getWorkspace()), same(actor), eq(AuditAction.SPRINT_CANCELLED),
                eq(team.sprintory_backend.audit.models.AuditTarget.sprint(sprint)), same(project),
                eq(AuditChange.of("status", "ACTIVE", "CANCELLED")),
                eq(AuditChange.of("reason", null, "Goal became obsolete")),
                eq(AuditChange.of("completedPoints", null, "12")),
                eq(AuditChange.of("unfinishedPoints", null, "3")),
                eq(AuditChange.of("carriedOver", null, "1 item to the backlog")));
        verify(events).publishEvent(isA(team.sprintory_backend.notification.events.SprintCancelled.class));
    }

    @Test void cancelNeedsAReasonAndAnActiveSprint() {
        cancellable();
        sprint.setStatus(SprintStatus.ACTIVE);
        assertThrows(BadRequestException.class, () -> service.cancel(actor, 20L, null));
        assertThrows(BadRequestException.class, () -> service.cancel(actor, 20L, new CancelSprintRequest("  ")));
        nothingWritten();
        for (SprintStatus status : List.of(SprintStatus.PLANNED, SprintStatus.COMPLETED, SprintStatus.CANCELLED)) {
            sprint.setStatus(status);
            assertThrows(SprintConflictException.class, () -> service.cancel(actor, 20L, new CancelSprintRequest("Why")));
        }
        verify(events, never()).publishEvent(any());
    }

    @Test void cancelChecksCancelSprintsNotManageSprints() {
        when(repository.findProjectId(20L)).thenReturn(Optional.of(10L));
        when(access.requireActive(10L, actor, ProjectPermission.CANCEL_SPRINTS))
                .thenThrow(new AccessDeniedException("Sprint permission required."));
        sprint.setStatus(SprintStatus.ACTIVE);
        assertThrows(AccessDeniedException.class, () -> service.cancel(actor, 20L, new CancelSprintRequest("Why")));
        verify(access, never()).requireActive(10L, actor, ProjectPermission.MANAGE_SPRINTS);
        verify(projects, never()).lockById(any());
        nothingWritten();
    }

    @Test void archivedWorkspacePreventsMutation() {
        when(repository.findProjectId(20L)).thenReturn(Optional.of(10L));
        when(access.requireActive(10L, actor, ProjectPermission.MANAGE_SPRINTS))
                .thenThrow(new WorkspaceArchivedException("Workspace is archived."));
        assertThrows(WorkspaceArchivedException.class, () -> service.delete(actor, 20L));
        verify(repository, never()).findById(20L);
    }
}
