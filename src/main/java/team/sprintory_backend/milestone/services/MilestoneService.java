package team.sprintory_backend.milestone.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.audit.models.AuditAction;
import team.sprintory_backend.audit.models.AuditChange;
import team.sprintory_backend.audit.models.AuditTarget;
import team.sprintory_backend.audit.services.AuditLogService;
import team.sprintory_backend.common.dto.UserRef;
import team.sprintory_backend.exceptionHandler.BadRequestException;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Milestones (R12, docs/milestones-docs.md). Reads need {@code VIEW_WORK}; every write takes the
 * project row lock, which serializes the name check, the limit of {@value #LIMIT} and linking.
 * Progress and status are calculated on every read.
 */
@Service
@RequiredArgsConstructor
public class MilestoneService {

    public static final int LIMIT = 50;
    private static final String NOT_FOUND = "Milestone not found.";
    private static final String PROJECT_NOT_FOUND = "Project not found.";
    private static final String TASK_NOT_FOUND = "Task not found.";

    private final MilestoneRepository milestones;
    private final TaskRepository tasks;
    private final ProjectRepository projects;
    private final ProjectAccess access;
    private final AuditLogService audit;
    private final TaskStatusHistoryService history;
    private final TaskApiService taskApi;

    /** §3.1: the project's live milestones in list order. */
    @Transactional(readOnly = true)
    public MilestoneListResponse list(User actor, Long projectId) {
        ProjectAccessResult checked = readable(actor, projectId, PROJECT_NOT_FOUND);
        List<MilestoneResponse> list = responses(milestones.findListByProjectId(projectId), checked);
        return new MilestoneListResponse(list, canChange(checked, ProjectPermission.MILESTONE_MANAGE),
                canChange(checked, ProjectPermission.MILESTONE_LINK), LIMIT);
    }

    /** §3.3. */
    @Transactional(readOnly = true)
    public MilestoneResponse get(User actor, Long milestoneId) {
        Milestone milestone = milestones.findById(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        ProjectAccessResult checked = readable(actor, milestone.getProject().getId(), NOT_FOUND);
        return responses(List.of(milestone), checked).get(0);
    }

    /** §3.2. */
    @Transactional
    public MilestoneResponse create(User actor, Long projectId, MilestoneRequest request) {
        ProjectAccessResult checked = writable(actor, projectId, ProjectPermission.MILESTONE_MANAGE, PROJECT_NOT_FOUND);
        if (milestones.countByProjectId(projectId) >= LIMIT) {
            throw MilestoneConflictException.limitReached(LIMIT);
        }
        if (milestones.existsByProjectIdAndNameIgnoreCase(projectId, request.name())) {
            throw MilestoneConflictException.nameTaken();
        }
        Project project = checked.project();
        Milestone milestone = milestones.save(new Milestone(project, request.name(), request.targetDate(),
                request.description(), actor));
        List<AuditChange> changes = new ArrayList<>(List.of(
                AuditChange.of("name", null, milestone.getName()),
                AuditChange.of("targetDate", null, milestone.getTargetDate().toString())));
        if (milestone.getDescription() != null) {
            changes.add(AuditChange.of("description", null, milestone.getDescription()));
        }
        audit.record(project.getWorkspace(), actor, AuditAction.MILESTONE_CREATED, AuditTarget.milestone(milestone),
                project, changes.toArray(AuditChange[]::new));
        return responses(List.of(milestone), checked).get(0);
    }

    /** §3.4: replaces all three fields; no audit entry when nothing changed. */
    @Transactional
    public MilestoneResponse update(User actor, Long milestoneId, MilestoneRequest request) {
        Long projectId = milestones.findProjectIdById(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        ProjectAccessResult checked = writable(actor, projectId, ProjectPermission.MILESTONE_MANAGE, NOT_FOUND);
        Milestone milestone = milestones.findById(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (milestones.existsByProjectIdAndNameIgnoreCaseAndIdNot(projectId, request.name(), milestoneId)) {
            throw MilestoneConflictException.nameTaken();
        }
        List<AuditChange> changes = new ArrayList<>();
        if (!request.name().equals(milestone.getName())) {
            changes.add(AuditChange.of("name", milestone.getName(), request.name()));
        }
        if (!request.targetDate().equals(milestone.getTargetDate())) {
            changes.add(AuditChange.of("targetDate", milestone.getTargetDate().toString(),
                    request.targetDate().toString()));
        }
        if (!Objects.equals(request.description(), milestone.getDescription())) {
            changes.add(AuditChange.of("description", milestone.getDescription(), request.description()));
        }
        if (!changes.isEmpty()) {
            milestone.setName(request.name());
            milestone.setTargetDate(request.targetDate());
            milestone.setDescription(request.description());
            Project project = checked.project();
            audit.record(project.getWorkspace(), actor, AuditAction.MILESTONE_UPDATED,
                    AuditTarget.milestone(milestone), project, changes.toArray(AuditChange[]::new));
        }
        return responses(List.of(milestone), checked).get(0);
    }

    /**
     * §3.5: a soft delete that unlinks every task, deleted ones included. Live tasks get a history
     * entry, and the audit entry counts them.
     */
    @Transactional
    public void delete(User actor, Long milestoneId) {
        Long projectId = milestones.findProjectIdById(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        ProjectAccessResult checked = writable(actor, projectId, ProjectPermission.MILESTONE_MANAGE, NOT_FOUND);
        Milestone milestone = milestones.findById(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        List<Task> linked = tasks.findByMilestoneId(milestoneId);
        for (Task task : linked) {
            link(task, null, actor);
        }
        tasks.clearMilestoneIncludingDeleted(milestoneId);
        milestone.setDeletedAt(Instant.now());
        Project project = checked.project();
        audit.record(project.getWorkspace(), actor, AuditAction.MILESTONE_DELETED, AuditTarget.milestone(milestone),
                project, AuditChange.of("unlinkedTasks", null, String.valueOf(linked.size())));
    }

    /** §3.6: back with no tasks; refused when its name was taken meanwhile or the project is full. */
    @Transactional
    public MilestoneResponse restore(User actor, Long milestoneId) {
        Long projectId = milestones.findProjectIdOfDeletedById(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        ProjectAccessResult checked = writable(actor, projectId, ProjectPermission.MILESTONE_MANAGE, NOT_FOUND);
        Milestone milestone = milestones.findDeletedById(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (milestones.existsByProjectIdAndNameIgnoreCase(projectId, milestone.getName())) {
            throw MilestoneConflictException.nameTaken();
        }
        if (milestones.countByProjectId(projectId) >= LIMIT) {
            throw MilestoneConflictException.limitReached(LIMIT);
        }
        milestone.setDeletedAt(null);
        Project project = checked.project();
        audit.record(project.getWorkspace(), actor, AuditAction.MILESTONE_RESTORED, AuditTarget.milestone(milestone),
                project);
        return responses(List.of(milestone), checked).get(0);
    }

    /**
     * §3.7: all or nothing. Every id must be a live top-level task of the milestone's project;
     * tasks of another milestone are moved, tasks already here are skipped.
     */
    @Transactional
    public MilestoneResponse linkTasks(User actor, Long milestoneId, MilestoneTasksRequest request) {
        Long projectId = milestones.findProjectIdById(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        ProjectAccessResult checked = writable(actor, projectId, ProjectPermission.MILESTONE_LINK, NOT_FOUND);
        Milestone milestone = milestones.findById(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        Set<Long> ids = new HashSet<>(request.taskIds());
        List<Task> found = tasks.findByIdInAndProjectIdAndParentIsNull(ids, projectId);
        if (found.size() != ids.size()) {
            throw new BadRequestException("Only live top-level tasks of this project can be linked.");
        }
        for (Task task : found) {
            link(task, milestone, actor);
        }
        return responses(List.of(milestone), checked).get(0);
    }

    /** §3.8: sets or clears one task's milestone; the same milestone again changes nothing. */
    @Transactional
    public TaskResponse setTaskMilestone(User actor, Long taskId, TaskMilestoneRequest request) {
        Long projectId = tasks.findProjectIdById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException(TASK_NOT_FOUND));
        writable(actor, projectId, ProjectPermission.MILESTONE_LINK, TASK_NOT_FOUND);
        Task task = tasks.findByIdAndProjectId(taskId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException(TASK_NOT_FOUND));
        if (task.getParent() != null) {
            throw new BadRequestException("Only top-level tasks can be linked to a milestone.");
        }
        Milestone milestone = null;
        if (request.milestoneId() != null) {
            milestone = milestones.findByIdAndProjectId(request.milestoneId(), projectId)
                    .orElseThrow(() -> new BadRequestException("The milestone does not belong to this project."));
        }
        link(task, milestone, actor);
        return taskApi.get(actor, projectId, taskId);
    }

    /** Links the task to {@code milestone} ({@code null} unlinks) with a history entry; nothing if unchanged. */
    private void link(Task task, Milestone milestone, User actor) {
        Milestone old = task.getMilestone();
        Long oldId = old == null ? null : old.getId();
        Long newId = milestone == null ? null : milestone.getId();
        if (Objects.equals(oldId, newId)) {
            return;
        }
        task.setMilestone(milestone);
        history.recordChange(task, TaskHistoryField.MILESTONE, old == null ? null : old.getName(),
                milestone == null ? null : milestone.getName(), actor);
    }

    /** The responses of several milestones of one project, with their progress read in one query. */
    private List<MilestoneResponse> responses(List<Milestone> list, ProjectAccessResult checked) {
        Map<Long, MilestoneProgress> progress = new HashMap<>();
        if (!list.isEmpty()) {
            for (Object[] row : tasks.countByMilestone(list.stream().map(Milestone::getId).toList(),
                    BoardColumnCategory.DONE)) {
                progress.put((Long) row[0], MilestoneProgress.of(number(row[1]), number(row[2]),
                        number(row[3]), number(row[4])));
            }
        }
        MilestoneProgress none = MilestoneProgress.of(0, 0, 0, 0);
        LocalDate today = LocalDate.now();
        boolean canManage = canChange(checked, ProjectPermission.MILESTONE_MANAGE);
        boolean canLink = canChange(checked, ProjectPermission.MILESTONE_LINK);
        return list.stream().map(milestone -> {
            MilestoneProgress p = progress.getOrDefault(milestone.getId(), none);
            return new MilestoneResponse(milestone.getId(), checked.project().getId(), milestone.getName(),
                    milestone.getDescription(), milestone.getTargetDate(),
                    MilestoneStatus.of(today, milestone.getTargetDate(), p.doneTasks(), p.totalTasks()), p,
                    toInstant(milestone.getCreatedAt()), UserRef.of(milestone.getCreatedByUser()),
                    canManage, canLink);
        }).toList();
    }

    /** The permission, an active workspace and an {@code ACTIVE} project: what the {@code can…} flags say. */
    private static boolean canChange(ProjectAccessResult checked, ProjectPermission permission) {
        return checked.can(permission)
                && !checked.workspaceMember().getWorkspace().isArchived()
                && checked.project().getLifecycleStatus() == ProjectLifecycleState.ACTIVE;
    }

    /** {@code VIEW_WORK}; a project the caller cannot see gives the resource's own 404. */
    private ProjectAccessResult readable(User actor, Long projectId, String notFoundMessage) {
        try {
            return access.require(projectId, actor, ProjectPermission.VIEW_WORK);
        } catch (ResourceNotFoundException e) {
            throw new ResourceNotFoundException(notFoundMessage);
        }
    }

    /**
     * The permission (403), an active workspace (409 {@code WORKSPACE_ARCHIVED}) and an active
     * project (409 {@code CONFLICT}), then the project row lock. A project the caller cannot see
     * gives the resource's own 404.
     */
    private ProjectAccessResult writable(User actor, Long projectId, ProjectPermission permission,
                                         String notFoundMessage) {
        ProjectAccessResult checked;
        try {
            checked = access.requireActive(projectId, actor, permission);
        } catch (ResourceNotFoundException e) {
            throw new ResourceNotFoundException(notFoundMessage);
        }
        if (checked.project().getLifecycleStatus() != ProjectLifecycleState.ACTIVE) {
            throw MilestoneConflictException.readOnlyProject();
        }
        projects.lockById(projectId);
        return checked;
    }

    /** Aggregates come back as Long or Integer depending on the column. */
    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }
}
