package team.sprintory_backend.milestone.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import team.sprintory_backend.audit.models.AuditAction;
import team.sprintory_backend.audit.models.AuditChange;
import team.sprintory_backend.audit.models.AuditTarget;
import team.sprintory_backend.audit.services.AuditLogService;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ErrorCode;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.milestone.dto.MilestoneListResponse;
import team.sprintory_backend.milestone.dto.MilestoneProgress;
import team.sprintory_backend.milestone.dto.MilestoneRequest;
import team.sprintory_backend.milestone.dto.MilestoneResponse;
import team.sprintory_backend.milestone.dto.MilestoneTasksRequest;
import team.sprintory_backend.milestone.dto.TaskMilestoneRequest;
import team.sprintory_backend.milestone.exceptions.MilestoneConflictException;
import team.sprintory_backend.milestone.models.Milestone;
import team.sprintory_backend.milestone.models.MilestoneStatus;
import team.sprintory_backend.milestone.repositories.MilestoneRepository;
import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectLifecycleState;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.project.services.ProjectAccessResult;
import team.sprintory_backend.task.dto.TaskResponse;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.models.TaskHistoryField;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.task.services.TaskApiService;
import team.sprintory_backend.task.services.TaskStatusHistoryService;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The milestone rules of docs/milestones-docs.md §3.1–3.8. */
class MilestoneServiceTest {

    private static final LocalDate FUTURE = LocalDate.now().plusDays(30);
    private static final LocalDate PAST = LocalDate.now().minusDays(1);

    private MilestoneRepository milestones;
    private TaskRepository tasks;
    private ProjectRepository projects;
    private ProjectAccess access;
    private AuditLogService audit;
    private TaskStatusHistoryService history;
    private TaskApiService taskApi;
    private MilestoneService service;

    private Workspace workspace;
    private Project project;
    private User actor;

    @BeforeEach
    void setUp() {
        milestones = mock(MilestoneRepository.class);
        tasks = mock(TaskRepository.class);
        projects = mock(ProjectRepository.class);
        access = mock(ProjectAccess.class);
        audit = mock(AuditLogService.class);
        history = mock(TaskStatusHistoryService.class);
        taskApi = mock(TaskApiService.class);
        service = new MilestoneService(milestones, tasks, projects, access, audit, history, taskApi);

        workspace = Workspace.builder().id(1L).name("Acme").slug("acme").build();
        project = Project.builder().id(10L).workspace(workspace).name("Mobile").key("MOB")
                .template(ProjectTemplate.KANBAN).build();
        actor = User.builder().id(9L).firstName("Linus").lastName("Torvalds").email("linus@test.invalid").build();
        when(milestones.save(any(Milestone.class))).thenAnswer(inv -> {
            Milestone saved = inv.getArgument(0);
            saved.setId(4L);
            return saved;
        });
    }

    private ProjectAccessResult accessAs(ProjectRole role) {
        WorkspaceMember member = WorkspaceMember.builder().workspace(workspace).user(actor).build();
        return new ProjectAccessResult(project, member, null, ProjectRole.permissionsOf(List.of(role)));
    }

    private Milestone milestone(long id, String name, LocalDate targetDate) {
        Milestone milestone = new Milestone(project, name, targetDate, null, actor);
        milestone.setId(id);
        return milestone;
    }

    /** The caller holds the permission in project 10, which is writable. */
    private void writableAs(ProjectRole role, ProjectPermission permission) {
        when(access.requireActive(10L, actor, permission)).thenReturn(accessAs(role));
        when(projects.lockById(10L)).thenReturn(Optional.of(project));
    }

    /** Milestone 4 is live in project 10, and the caller may manage milestones. */
    private Milestone liveMilestoneManagedAs(ProjectRole role) {
        Milestone milestone = milestone(4L, "Beta release", FUTURE);
        when(milestones.findProjectIdById(4L)).thenReturn(Optional.of(10L));
        writableAs(role, ProjectPermission.MILESTONE_MANAGE);
        when(milestones.findById(4L)).thenReturn(Optional.of(milestone));
        return milestone;
    }

    // --- list and read (§3.1, §3.3) ---

    @Test
    void listCalculatesProgressAndStatusFromOneAggregateQuery() {
        when(access.require(10L, actor, ProjectPermission.VIEW_WORK)).thenReturn(accessAs(ProjectRole.PROJECT_MANAGER));
        Milestone beta = milestone(4L, "Beta release", FUTURE);
        Milestone demo = milestone(5L, "Client demo", PAST);
        Milestone empty = milestone(6L, "Launch", FUTURE);
        when(milestones.findListByProjectId(10L)).thenReturn(List.of(demo, beta, empty));
        // criterion 15 for beta; demo has an open unestimated task; the aggregate types vary
        when(tasks.countByMilestone(anyCollection(), eq(BoardColumnCategory.DONE))).thenReturn(List.of(
                new Object[]{4L, 3L, 1L, 10L, 5L},
                new Object[]{5L, 2L, 1, 0L, 0}));

        MilestoneListResponse list = service.list(actor, 10L);

        assertEquals(List.of(5L, 4L, 6L), list.milestones().stream().map(MilestoneResponse::id).toList(),
                "the repository's order");
        MilestoneResponse betaResponse = list.milestones().get(1);
        assertEquals(new MilestoneProgress(3, 1, 10, 5, MilestoneProgress.Basis.POINTS, 50), betaResponse.progress());
        assertEquals(MilestoneStatus.UPCOMING, betaResponse.status());
        assertEquals(MilestoneStatus.OVERDUE, list.milestones().get(0).status());
        assertEquals(MilestoneProgress.Basis.TASKS, list.milestones().get(0).progress().basis());
        assertEquals(MilestoneStatus.EMPTY, list.milestones().get(2).status());
        assertNull(list.milestones().get(2).progress().percent());
        assertEquals("Linus Torvalds", betaResponse.createdBy().name());
        assertTrue(list.canManage());
        assertTrue(list.canLink());
        assertEquals(50, list.limit());
        verify(tasks).countByMilestone(List.of(5L, 4L, 6L), BoardColumnCategory.DONE);
    }

    @Test
    void anEmptyListRunsNoAggregateQueryButStillCarriesTheFlags() {
        when(access.require(10L, actor, ProjectPermission.VIEW_WORK)).thenReturn(accessAs(ProjectRole.MEMBER));
        when(milestones.findListByProjectId(10L)).thenReturn(List.of());

        MilestoneListResponse list = service.list(actor, 10L);

        assertTrue(list.milestones().isEmpty());
        assertFalse(list.canManage(), "a Developer cannot manage");
        assertTrue(list.canLink(), "but can link");
        verifyNoInteractions(tasks);
    }

    @Test
    void aViewerAndAReadOnlyProjectGetNoActions() {
        when(milestones.findListByProjectId(10L)).thenReturn(List.of());
        when(access.require(10L, actor, ProjectPermission.VIEW_WORK)).thenReturn(accessAs(ProjectRole.VIEWER));
        MilestoneListResponse viewer = service.list(actor, 10L);
        assertFalse(viewer.canManage());
        assertFalse(viewer.canLink());

        when(access.require(10L, actor, ProjectPermission.VIEW_WORK)).thenReturn(accessAs(ProjectRole.PROJECT_MANAGER));
        project.setLifecycleStatus(ProjectLifecycleState.COMPLETED);
        MilestoneListResponse completed = service.list(actor, 10L);
        assertFalse(completed.canManage());
        assertFalse(completed.canLink());

        project.setLifecycleStatus(ProjectLifecycleState.ACTIVE);
        workspace.setArchivedAt(java.time.Instant.now());
        assertFalse(service.list(actor, 10L).canManage(), "archived workspace");
    }

    @Test
    void aProjectTheCallerCannotSeeIs404() {
        when(access.require(10L, actor, ProjectPermission.VIEW_WORK))
                .thenThrow(new ResourceNotFoundException("Project not found."));

        var e = assertThrows(ResourceNotFoundException.class, () -> service.list(actor, 10L));
        assertEquals("Project not found.", e.getMessage());
    }

    @Test
    void getAnswersTheSame404ForAMissingMilestoneAndAnInvisibleProject() {
        when(milestones.findById(4L)).thenReturn(Optional.empty());
        assertEquals("Milestone not found.",
                assertThrows(ResourceNotFoundException.class, () -> service.get(actor, 4L)).getMessage());

        when(milestones.findById(5L)).thenReturn(Optional.of(milestone(5L, "Beta release", FUTURE)));
        when(access.require(10L, actor, ProjectPermission.VIEW_WORK))
                .thenThrow(new ResourceNotFoundException("Project not found."));
        assertEquals("Milestone not found.",
                assertThrows(ResourceNotFoundException.class, () -> service.get(actor, 5L)).getMessage());
    }

    @Test
    void getReturnsOneMilestoneWithItsProgress() {
        when(milestones.findById(4L)).thenReturn(Optional.of(milestone(4L, "Beta release", FUTURE)));
        when(access.require(10L, actor, ProjectPermission.VIEW_WORK)).thenReturn(accessAs(ProjectRole.VIEWER));
        List<Object[]> rows = List.<Object[]>of(new Object[]{4L, 2L, 2L, 0L, 0L});
        when(tasks.countByMilestone(List.of(4L), BoardColumnCategory.DONE)).thenReturn(rows);

        MilestoneResponse response = service.get(actor, 4L);

        assertEquals(MilestoneStatus.REACHED, response.status());
        assertEquals(10L, response.projectId());
    }

    // --- create (§3.2) ---

    @Test
    void createSavesTheMilestoneAndLogsIt() {
        writableAs(ProjectRole.PRODUCT_OWNER, ProjectPermission.MILESTONE_MANAGE);

        MilestoneResponse created = service.create(actor, 10L,
                new MilestoneRequest("Beta release", FUTURE, "Pilot build"));

        assertEquals(4L, created.id());
        assertEquals("Beta release", created.name());
        assertEquals("Pilot build", created.description());
        assertEquals(MilestoneStatus.EMPTY, created.status());
        verify(audit).record(workspace, actor, AuditAction.MILESTONE_CREATED,
                AuditTarget.milestone(milestone(4L, "Beta release", FUTURE)), project,
                AuditChange.of("name", null, "Beta release"),
                AuditChange.of("targetDate", null, FUTURE.toString()),
                AuditChange.of("description", null, "Pilot build"));
    }

    @Test
    void createLogsNoDescriptionWhenThereIsNone() {
        writableAs(ProjectRole.PROJECT_MANAGER, ProjectPermission.MILESTONE_MANAGE);

        service.create(actor, 10L, new MilestoneRequest("Beta release", PAST, "  "));

        verify(audit).record(workspace, actor, AuditAction.MILESTONE_CREATED,
                AuditTarget.milestone(milestone(4L, "Beta release", PAST)), project,
                AuditChange.of("name", null, "Beta release"),
                AuditChange.of("targetDate", null, PAST.toString()));
    }

    @Test
    void createChecksTheLimitAndTheNameUnderTheProjectLock() {
        writableAs(ProjectRole.PROJECT_MANAGER, ProjectPermission.MILESTONE_MANAGE);

        service.create(actor, 10L, new MilestoneRequest("Beta release", FUTURE, null));

        var order = inOrder(access, projects, milestones);
        order.verify(access).requireActive(10L, actor, ProjectPermission.MILESTONE_MANAGE);
        order.verify(projects).lockById(10L);
        order.verify(milestones).countByProjectId(10L);
        order.verify(milestones).existsByProjectIdAndNameIgnoreCase(10L, "Beta release");
        order.verify(milestones).save(any(Milestone.class));
    }

    @Test
    void the51stMilestoneIsRefused() {
        writableAs(ProjectRole.PROJECT_MANAGER, ProjectPermission.MILESTONE_MANAGE);
        when(milestones.countByProjectId(10L)).thenReturn(50L);

        var e = assertThrows(MilestoneConflictException.class,
                () -> service.create(actor, 10L, new MilestoneRequest("Beta release", FUTURE, null)));

        assertEquals(ErrorCode.MILESTONE_LIMIT_REACHED, e.getCode());
        verify(milestones, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void aDuplicateNameIsRefused() {
        writableAs(ProjectRole.PROJECT_MANAGER, ProjectPermission.MILESTONE_MANAGE);
        when(milestones.existsByProjectIdAndNameIgnoreCase(10L, "beta release")).thenReturn(true);

        var e = assertThrows(MilestoneConflictException.class,
                () -> service.create(actor, 10L, new MilestoneRequest("beta release", FUTURE, null)));

        assertEquals(ErrorCode.MILESTONE_NAME_TAKEN, e.getCode());
        verify(milestones, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void aCompletedProjectIsReadOnlyAndNothingIsLocked() {
        writableAs(ProjectRole.PROJECT_MANAGER, ProjectPermission.MILESTONE_MANAGE);
        project.setLifecycleStatus(ProjectLifecycleState.ARCHIVED);

        var e = assertThrows(MilestoneConflictException.class,
                () -> service.create(actor, 10L, new MilestoneRequest("Beta release", FUTURE, null)));

        assertEquals(ErrorCode.CONFLICT, e.getCode());
        verify(projects, never()).lockById(any());
        verify(milestones, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void withoutThePermissionNothingHappens() {
        when(access.requireActive(10L, actor, ProjectPermission.MILESTONE_MANAGE))
                .thenThrow(new AccessDeniedException("Project permission required: MILESTONE_MANAGE"));

        assertThrows(AccessDeniedException.class,
                () -> service.create(actor, 10L, new MilestoneRequest("Beta release", FUTURE, null)));

        verify(milestones, never()).save(any());
        verifyNoInteractions(audit);
    }

    // --- update (§3.4) ---

    @Test
    void updateLocksTheProjectBeforeLoadingTheMilestone() {
        liveMilestoneManagedAs(ProjectRole.PROJECT_MANAGER);

        service.update(actor, 4L, new MilestoneRequest("Beta release", FUTURE, null));

        var order = inOrder(milestones, access, projects);
        order.verify(milestones).findProjectIdById(4L);
        order.verify(access).requireActive(10L, actor, ProjectPermission.MILESTONE_MANAGE);
        order.verify(projects).lockById(10L);
        order.verify(milestones).findById(4L);
    }

    @Test
    void updateLogsOnlyTheFieldsThatChanged() {
        Milestone milestone = liveMilestoneManagedAs(ProjectRole.PRODUCT_OWNER);
        LocalDate later = FUTURE.plusDays(7);

        MilestoneResponse updated = service.update(actor, 4L, new MilestoneRequest("Beta release", later, "Pilot"));

        assertEquals(later, milestone.getTargetDate());
        assertEquals("Pilot", updated.description());
        verify(audit).record(workspace, actor, AuditAction.MILESTONE_UPDATED, AuditTarget.milestone(milestone),
                project, AuditChange.of("targetDate", FUTURE.toString(), later.toString()),
                AuditChange.of("description", null, "Pilot"));
    }

    @Test
    void anUnchangedSaveLogsNothing() {
        liveMilestoneManagedAs(ProjectRole.PROJECT_MANAGER);

        service.update(actor, 4L, new MilestoneRequest("Beta release", FUTURE, " "));

        verifyNoInteractions(audit);
    }

    @Test
    void changingOnlyTheCaseOfTheNameIsAllowedAndLogged() {
        Milestone milestone = liveMilestoneManagedAs(ProjectRole.PROJECT_MANAGER);

        service.update(actor, 4L, new MilestoneRequest("BETA release", FUTURE, null));

        assertEquals("BETA release", milestone.getName());
        verify(milestones).existsByProjectIdAndNameIgnoreCaseAndIdNot(10L, "BETA release", 4L);
        verify(audit).record(workspace, actor, AuditAction.MILESTONE_UPDATED, AuditTarget.milestone(milestone),
                project, AuditChange.of("name", "Beta release", "BETA release"));
    }

    @Test
    void renamingToAnotherMilestonesNameIsRefused() {
        Milestone milestone = liveMilestoneManagedAs(ProjectRole.PROJECT_MANAGER);
        when(milestones.existsByProjectIdAndNameIgnoreCaseAndIdNot(10L, "Client demo", 4L)).thenReturn(true);

        var e = assertThrows(MilestoneConflictException.class,
                () -> service.update(actor, 4L, new MilestoneRequest("Client demo", FUTURE, null)));

        assertEquals(ErrorCode.MILESTONE_NAME_TAKEN, e.getCode());
        assertEquals("Beta release", milestone.getName());
        verifyNoInteractions(audit);
    }

    @Test
    void updatingAMissingMilestoneIs404BeforeAnyCheck() {
        when(milestones.findProjectIdById(4L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.update(actor, 4L, new MilestoneRequest("Beta release", FUTURE, null)));

        verifyNoInteractions(access, projects, audit);
    }

    @Test
    void anInvisibleProjectGivesTheMilestones404() {
        when(milestones.findProjectIdById(4L)).thenReturn(Optional.of(10L));
        when(access.requireActive(10L, actor, ProjectPermission.MILESTONE_MANAGE))
                .thenThrow(new ResourceNotFoundException("Project not found."));

        var e = assertThrows(ResourceNotFoundException.class, () -> service.delete(actor, 4L));

        assertEquals("Milestone not found.", e.getMessage());
        verifyNoInteractions(audit);
    }

    // --- delete (§3.5) ---

    @Test
    void deleteIsSoftAndLogged() {
        Milestone milestone = liveMilestoneManagedAs(ProjectRole.PRODUCT_OWNER);

        service.delete(actor, 4L);

        assertNotNull(milestone.getDeletedAt());
        verify(milestones, never()).delete(any());
        verify(tasks).clearMilestoneIncludingDeleted(4L);
        verifyNoInteractions(history);
        verify(audit).record(workspace, actor, AuditAction.MILESTONE_DELETED, AuditTarget.milestone(milestone),
                project, AuditChange.of("unlinkedTasks", null, "0"));
    }

    @Test
    void deleteUnlinksEveryTaskAndCountsTheLiveOnes() {
        Milestone milestone = liveMilestoneManagedAs(ProjectRole.PROJECT_MANAGER);
        Task first = task(1L, milestone);
        Task second = task(2L, milestone);
        when(tasks.findByMilestoneId(4L)).thenReturn(List.of(first, second));

        service.delete(actor, 4L);

        assertNull(first.getMilestone());
        assertNull(second.getMilestone());
        verify(history).recordChange(first, TaskHistoryField.MILESTONE, "Beta release", null, actor);
        verify(history).recordChange(second, TaskHistoryField.MILESTONE, "Beta release", null, actor);
        verify(tasks).clearMilestoneIncludingDeleted(4L);
        verify(audit).record(workspace, actor, AuditAction.MILESTONE_DELETED, AuditTarget.milestone(milestone),
                project, AuditChange.of("unlinkedTasks", null, "2"));
    }

    // --- restore (§3.6) ---

    /** Milestone 4 is deleted in project 10, and the caller may manage milestones. */
    private Milestone deletedMilestoneManagedAs(ProjectRole role) {
        Milestone milestone = milestone(4L, "Beta release", FUTURE);
        milestone.setDeletedAt(java.time.Instant.now());
        when(milestones.findProjectIdOfDeletedById(4L)).thenReturn(Optional.of(10L));
        writableAs(role, ProjectPermission.MILESTONE_MANAGE);
        when(milestones.findDeletedById(4L)).thenReturn(Optional.of(milestone));
        return milestone;
    }

    @Test
    void restoreBringsTheMilestoneBackEmptyAndLogsIt() {
        Milestone milestone = deletedMilestoneManagedAs(ProjectRole.PROJECT_MANAGER);

        MilestoneResponse restored = service.restore(actor, 4L);

        assertNull(milestone.getDeletedAt());
        assertEquals(MilestoneStatus.EMPTY, restored.status());
        verify(audit).record(workspace, actor, AuditAction.MILESTONE_RESTORED, AuditTarget.milestone(milestone),
                project);
        var order = inOrder(milestones, projects);
        order.verify(milestones).findProjectIdOfDeletedById(4L);
        order.verify(projects).lockById(10L);
        order.verify(milestones).findDeletedById(4L);
    }

    @Test
    void restoreIsRefusedWhenTheNameWasTakenMeanwhile() {
        Milestone milestone = deletedMilestoneManagedAs(ProjectRole.PROJECT_MANAGER);
        when(milestones.existsByProjectIdAndNameIgnoreCase(10L, "Beta release")).thenReturn(true);

        var e = assertThrows(MilestoneConflictException.class, () -> service.restore(actor, 4L));

        assertEquals(ErrorCode.MILESTONE_NAME_TAKEN, e.getCode());
        assertNotNull(milestone.getDeletedAt());
        verifyNoInteractions(audit);
    }

    @Test
    void restoreIsRefusedWhenTheProjectIsFull() {
        Milestone milestone = deletedMilestoneManagedAs(ProjectRole.PROJECT_MANAGER);
        when(milestones.countByProjectId(10L)).thenReturn(50L);

        var e = assertThrows(MilestoneConflictException.class, () -> service.restore(actor, 4L));

        assertEquals(ErrorCode.MILESTONE_LIMIT_REACHED, e.getCode());
        assertNotNull(milestone.getDeletedAt());
        verifyNoInteractions(audit);
    }

    @Test
    void restoringAMilestoneThatIsNotDeletedIs404() {
        when(milestones.findProjectIdOfDeletedById(4L)).thenReturn(Optional.empty());

        var e = assertThrows(ResourceNotFoundException.class, () -> service.restore(actor, 4L));

        assertEquals("Milestone not found.", e.getMessage());
        verifyNoInteractions(access, audit);
    }

    // --- link several tasks (§3.7) ---

    private Task task(long id, Milestone milestone) {
        return Task.builder().id(id).project(project).key("MOB-" + id).title("Task " + id)
                .milestone(milestone).build();
    }

    /** Milestone 4 is live in project 10, and the caller may link tasks. */
    private Milestone liveMilestoneLinkableAs(ProjectRole role) {
        Milestone milestone = milestone(4L, "Beta release", FUTURE);
        when(milestones.findProjectIdById(4L)).thenReturn(Optional.of(10L));
        writableAs(role, ProjectPermission.MILESTONE_LINK);
        when(milestones.findById(4L)).thenReturn(Optional.of(milestone));
        return milestone;
    }

    @Test
    void linkTasksLinksAndMovesButSkipsTasksAlreadyThere() {
        Milestone beta = liveMilestoneLinkableAs(ProjectRole.MEMBER);
        Milestone demo = milestone(5L, "Client demo", PAST);
        Task unlinked = task(1L, null);
        Task elsewhere = task(2L, demo);
        Task already = task(3L, beta);
        when(tasks.findByIdInAndProjectIdAndParentIsNull(anyCollection(), eq(10L)))
                .thenReturn(List.of(unlinked, elsewhere, already));

        MilestoneResponse response = service.linkTasks(actor, 4L, new MilestoneTasksRequest(List.of(1L, 2L, 3L, 3L)));

        assertEquals(4L, response.id());
        assertSame(beta, unlinked.getMilestone());
        assertSame(beta, elsewhere.getMilestone());
        verify(history).recordChange(unlinked, TaskHistoryField.MILESTONE, null, "Beta release", actor);
        verify(history).recordChange(elsewhere, TaskHistoryField.MILESTONE, "Client demo", "Beta release", actor);
        verifyNoMoreInteractions(history);
        verify(tasks).findByIdInAndProjectIdAndParentIsNull(java.util.Set.of(1L, 2L, 3L), 10L);
        verifyNoInteractions(audit);
        var order = inOrder(milestones, access, projects);
        order.verify(milestones).findProjectIdById(4L);
        order.verify(access).requireActive(10L, actor, ProjectPermission.MILESTONE_LINK);
        order.verify(projects).lockById(10L);
        order.verify(milestones).findById(4L);
    }

    @Test
    void linkTasksIsAllOrNothing() {
        liveMilestoneLinkableAs(ProjectRole.MEMBER);
        Task unlinked = task(1L, null);
        // task 2 is a subtask, deleted, missing or in another project: the query does not return it
        when(tasks.findByIdInAndProjectIdAndParentIsNull(anyCollection(), eq(10L))).thenReturn(List.of(unlinked));

        var e = assertThrows(BadRequestException.class,
                () -> service.linkTasks(actor, 4L, new MilestoneTasksRequest(List.of(1L, 2L))));

        assertEquals("Only live top-level tasks of this project can be linked.", e.getMessage());
        assertNull(unlinked.getMilestone());
        verifyNoInteractions(history);
    }

    // --- set or clear one task's milestone (§3.8) ---

    /** Task 7 is live in project 10, and the caller may link tasks. */
    private Task liveTaskLinkableAs(ProjectRole role, Milestone current) {
        Task task = task(7L, current);
        when(tasks.findProjectIdById(7L)).thenReturn(Optional.of(10L));
        writableAs(role, ProjectPermission.MILESTONE_LINK);
        when(tasks.findByIdAndProjectId(7L, 10L)).thenReturn(Optional.of(task));
        return task;
    }

    @Test
    void setTaskMilestoneLinksTheTaskUnderTheLockAndAnswersTheTask() {
        Task task = liveTaskLinkableAs(ProjectRole.MEMBER, null);
        Milestone beta = milestone(4L, "Beta release", FUTURE);
        when(milestones.findByIdAndProjectId(4L, 10L)).thenReturn(Optional.of(beta));
        TaskResponse answer = mock(TaskResponse.class);
        when(taskApi.get(actor, 10L, 7L)).thenReturn(answer);

        assertSame(answer, service.setTaskMilestone(actor, 7L, new TaskMilestoneRequest(4L)));

        assertSame(beta, task.getMilestone());
        verify(history).recordChange(task, TaskHistoryField.MILESTONE, null, "Beta release", actor);
        var order = inOrder(tasks, access, projects, taskApi);
        order.verify(tasks).findProjectIdById(7L);
        order.verify(access).requireActive(10L, actor, ProjectPermission.MILESTONE_LINK);
        order.verify(projects).lockById(10L);
        order.verify(tasks).findByIdAndProjectId(7L, 10L);
        order.verify(taskApi).get(actor, 10L, 7L);
    }

    @Test
    void setTaskMilestoneMovesAndUnlinks() {
        Milestone demo = milestone(5L, "Client demo", PAST);
        Task task = liveTaskLinkableAs(ProjectRole.SCRUM_MASTER, demo);
        Milestone beta = milestone(4L, "Beta release", FUTURE);
        when(milestones.findByIdAndProjectId(4L, 10L)).thenReturn(Optional.of(beta));

        service.setTaskMilestone(actor, 7L, new TaskMilestoneRequest(4L));
        service.setTaskMilestone(actor, 7L, new TaskMilestoneRequest(null));

        assertNull(task.getMilestone());
        verify(history).recordChange(task, TaskHistoryField.MILESTONE, "Client demo", "Beta release", actor);
        verify(history).recordChange(task, TaskHistoryField.MILESTONE, "Beta release", null, actor);
        verify(milestones, times(1)).findByIdAndProjectId(any(), any());
    }

    @Test
    void theSameMilestoneAgainOrUnlinkingAnUnlinkedTaskChangesNothing() {
        Milestone beta = milestone(4L, "Beta release", FUTURE);
        Task task = liveTaskLinkableAs(ProjectRole.MEMBER, beta);
        when(milestones.findByIdAndProjectId(4L, 10L)).thenReturn(Optional.of(beta));

        service.setTaskMilestone(actor, 7L, new TaskMilestoneRequest(4L));
        assertSame(beta, task.getMilestone());

        task.setMilestone(null);
        service.setTaskMilestone(actor, 7L, new TaskMilestoneRequest(null));

        verifyNoInteractions(history);
        verify(taskApi, times(2)).get(actor, 10L, 7L);
    }

    @Test
    void aSubtaskCannotBeLinked() {
        Task subtask = liveTaskLinkableAs(ProjectRole.MEMBER, null);
        subtask.setParent(task(1L, null));

        var e = assertThrows(BadRequestException.class,
                () -> service.setTaskMilestone(actor, 7L, new TaskMilestoneRequest(4L)));

        assertEquals("Only top-level tasks can be linked to a milestone.", e.getMessage());
        verifyNoInteractions(history);
    }

    @Test
    void aMilestoneOfAnotherProjectOrAMissingOneIs400() {
        Task task = liveTaskLinkableAs(ProjectRole.MEMBER, null);
        when(milestones.findByIdAndProjectId(99L, 10L)).thenReturn(Optional.empty());

        var e = assertThrows(BadRequestException.class,
                () -> service.setTaskMilestone(actor, 7L, new TaskMilestoneRequest(99L)));

        assertEquals("The milestone does not belong to this project.", e.getMessage());
        assertNull(task.getMilestone());
        verifyNoInteractions(history);
    }

    @Test
    void aMissingTaskOrAnInvisibleProjectIsTheTasks404() {
        when(tasks.findProjectIdById(7L)).thenReturn(Optional.empty());
        assertEquals("Task not found.", assertThrows(ResourceNotFoundException.class,
                () -> service.setTaskMilestone(actor, 7L, new TaskMilestoneRequest(4L))).getMessage());
        verifyNoInteractions(access, projects);

        when(tasks.findProjectIdById(8L)).thenReturn(Optional.of(10L));
        when(access.requireActive(10L, actor, ProjectPermission.MILESTONE_LINK))
                .thenThrow(new ResourceNotFoundException("Project not found."));
        assertEquals("Task not found.", assertThrows(ResourceNotFoundException.class,
                () -> service.setTaskMilestone(actor, 8L, new TaskMilestoneRequest(4L))).getMessage());
        verifyNoInteractions(history);
    }
}
