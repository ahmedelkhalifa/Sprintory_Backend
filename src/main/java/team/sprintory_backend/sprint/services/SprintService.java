package team.sprintory_backend.sprint.services;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.notification.events.SprintStarted;
import team.sprintory_backend.notification.events.SprintCancelled;
import team.sprintory_backend.notification.events.SprintCompleted;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.dto.UpdateProjectRequest;
import team.sprintory_backend.project.services.ProjectService;
import team.sprintory_backend.project.models.ProjectLifecycleState;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.sprint.dto.*;
import team.sprintory_backend.sprint.exceptions.*;
import team.sprintory_backend.sprint.models.*;
import team.sprintory_backend.sprint.repositories.*;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.task.services.TaskRanks;
import team.sprintory_backend.task.services.TaskStatusHistoryService;
import team.sprintory_backend.audit.models.AuditAction;
import team.sprintory_backend.audit.models.AuditChange;
import team.sprintory_backend.audit.models.AuditTarget;
import team.sprintory_backend.audit.services.AuditLogService;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** PMP-119: durable Scrum sprint planning, serialized by the project's database row lock. */
@Service @RequiredArgsConstructor
public class SprintService {
    private final SprintRepository sprints;
    private final SprintAvailabilityRepository availability;
    private final SprintCommitmentRepository commitments;
    private final SprintScopeChangeRepository scopeChanges;
    private final ProjectAccess access;
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final UserRepository users;
    private final TaskRepository tasks;
    private final ApplicationEventPublisher events;
    private final ProjectService projectSettings;
    private final SprintScopeHistoryWriter scopeWriter;
    private final SprintOutcomeRepository outcomes;
    private final TaskStatusHistoryService taskHistory;
    private final AuditLogService audit;

    @Transactional(readOnly = true)
    public int projectSprintLength(User actor, Long projectId) {
        Project project = access.require(projectId, actor, ProjectPermission.VIEW_PROJECT).project();
        ensureScrum(project);
        return project.getSprintLengthDays();
    }

    @Transactional
    public int setProjectSprintLength(User actor, Long projectId, SprintLengthRequest request) {
        Project project = access.requireActive(projectId, actor, ProjectPermission.EDIT_SETTINGS).project();
        ensureScrum(project);
        if (project.getLifecycleStatus() != ProjectLifecycleState.ACTIVE) {
            throw new SprintConflictException("A completed or archived project is read-only.");
        }
        return projectSettings.update(actor, projectId,
                new UpdateProjectRequest(null, null, request.days())).sprintLengthDays();
    }

    @Transactional(readOnly = true)
    public List<SprintResponse> list(User actor, Long projectId) {
        readable(actor, projectId);
        // Ended sprints (closed or cancelled) are read through GET /sprints/{id}.
        return sprints.findByProjectIdAndStatusInOrderByNumberAsc(projectId,
                        List.of(SprintStatus.PLANNED, SprintStatus.ACTIVE))
                .stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public SprintResponse get(User actor, Long sprintId) {
        Sprint sprint = find(sprintId);
        readable(actor, sprint.getProject().getId());
        return response(sprint);
    }

    @Transactional
    public SprintResponse create(User actor, Long projectId, SprintRequest request) {
        Project project = writable(actor, projectId, ProjectPermission.MANAGE_SPRINTS);
        if (Boolean.TRUE.equals(request.clearCapacity()) && request.capacity() != null) {
            throw new BadRequestException("Cannot set and clear capacity together.");
        }
        int number = sprints.maxNumber(projectId) + 1;
        LocalDate[] dates = createDates(project, request.startDate(), request.endDate());
        validateDates(projectId, null, dates[0], dates[1]);
        String name = request.name() == null || request.name().isBlank()
                ? project.getKey() + " Sprint " + number : requiredName(request.name());
        Sprint sprint = new Sprint(project, number, name, trim(request.goal()), request.capacity(), dates[0], dates[1]);
        return response(sprints.saveAndFlush(sprint));
    }

    @Transactional
    public SprintResponse update(User actor, Long sprintId, SprintRequest request) {
        Sprint sprint = writableSprint(actor, sprintId, ProjectPermission.MANAGE_SPRINTS);
        if (sprint.getStatus().isEnded()) throw new SprintConflictException("An ended sprint can't be changed.");
        if (request.name() != null) sprint.setName(requiredName(request.name()));
        if (request.goal() != null) sprint.setGoal(trim(request.goal()));
        if (Boolean.TRUE.equals(request.clearCapacity())) {
            if (request.capacity() != null) throw new BadRequestException("Cannot set and clear capacity together.");
            sprint.setCapacity(null);
        } else if (request.capacity() != null) sprint.setCapacity(request.capacity());
        if (request.startDate() != null || request.endDate() != null) {
            if (request.startDate() == null || request.endDate() == null) {
                throw new BadRequestException("Provide both sprint dates.");
            }
            LocalDate from = request.startDate();
            LocalDate to = request.endDate();
            validateDates(sprint.getProject().getId(), sprint.getId(), from, to);
            sprint.setStartDate(from);
            sprint.setEndDate(to);
        }
        return response(sprints.saveAndFlush(sprint));
    }

    @Transactional
    public void delete(User actor, Long sprintId) {
        Sprint sprint = writableSprint(actor, sprintId, ProjectPermission.MANAGE_SPRINTS);
        if (sprint.getStatus() != SprintStatus.PLANNED) throw new SprintConflictException("Only a planned sprint can be deleted.");
        // Keep managed live tasks in sync with the native bulk update (which must also
        // unlink soft-deleted tasks hidden by Task's @SQLRestriction).
        for (Task task : tasks.findBySprintIdAndParentIsNullOrderBySprintPositionAscIdAsc(sprintId)) {
            task.setSprint(null);
            task.setSprintPosition(null);
        }
        tasks.flush();
        tasks.clearSprintAssignmentsIncludingDeleted(sprintId);
        // Original backlog_rank remains unchanged.
        sprints.delete(sprint);
    }

    @Transactional
    public SprintResponse start(User actor, Long sprintId, StartSprintRequest request) {
        Sprint sprint = writableSprint(actor, sprintId, ProjectPermission.MANAGE_SPRINTS);
        Project project = sprint.getProject();
        if (sprint.getStatus() != SprintStatus.PLANNED) throw new SprintConflictException("Only a planned sprint can be started.");
        if (sprints.findFirstByProjectIdAndStatus(project.getId(), SprintStatus.ACTIVE).isPresent()) {
            throw new SprintAlreadyActiveException();
        }
        String goal = request != null && request.goal() != null ? trim(request.goal()) : sprint.getGoal();
        if (goal == null || goal.isBlank()) throw new BadRequestException("Add a Sprint Goal first.");
        if (request != null && ((request.startDate() == null) != (request.endDate() == null))) {
            throw new BadRequestException("Provide both sprint dates.");
        }
        LocalDate from = request != null && request.startDate() != null ? request.startDate() : sprint.getStartDate();
        LocalDate to = request != null && request.endDate() != null ? request.endDate() : sprint.getEndDate();
        validateDates(project.getId(), sprintId, from, to);
        List<Task> selected = tasks.findBySprintIdAndParentIsNullOrderBySprintPositionAscIdAsc(sprintId);
        if (selected.isEmpty()) throw new BadRequestException("Add at least one backlog item before starting the sprint.");
        sprint.setGoal(goal);
        sprint.setStartDate(from);
        sprint.setEndDate(to);
        sprint.setStatus(SprintStatus.ACTIVE);
        sprint.setCommittedPoints(selected.stream().mapToInt(t -> t.getEstimate() == null ? 0 : t.getEstimate()).sum());
        Sprint saved = sprints.saveAndFlush(sprint);
        List<SprintCommitment> snapshot = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            Task task = selected.get(i);
            snapshot.add(new SprintCommitment(saved, task.getId(), task.getKey(), task.getEstimate(), i));
        }
        commitments.saveAll(snapshot);
        events.publishEvent(new SprintStarted(project, actor, sprint.getName()));
        return response(saved);
    }

    @Transactional
    public void move(User actor, Long projectId, MoveSprintTaskRequest request) {
        writable(actor, projectId, ProjectPermission.MANAGE_SPRINTS);
        Task task = tasks.findByIdAndProjectId(request.taskId(), projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found."));
        if (task.getParent() != null) throw new BadRequestException("Subtasks follow their parent and cannot be planned separately.");
        Sprint source = task.getSprint();
        Sprint target = request.sprintId() == null ? null : findInProject(projectId, request.sprintId());
        if (source != null && source.getStatus().isEnded()) {
            throw new SprintConflictException("Tasks from an ended sprint cannot be moved.");
        }
        if (target != null && target.getStatus().isEnded()) {
            throw new SprintConflictException("An ended sprint can't accept work.");
        }
        if (Objects.equals(source == null ? null : source.getId(), request.sprintId()) &&
                target == null) return;
        if (request.afterTaskId() != null && Objects.equals(request.afterTaskId(), task.getId())) {
            throw new BadRequestException("A task cannot follow itself.");
        }

        // Read the old and new ordered lists before changing the task's association.
        List<Task> oldList = source == null ? new ArrayList<>()
                : new ArrayList<>(tasks.findBySprintIdAndParentIsNullOrderBySprintPositionAscIdAsc(source.getId()));
        List<Task> newList = target == null ? new ArrayList<>()
                : (source != null && source.getId().equals(target.getId()))
                   ? oldList : new ArrayList<>(tasks.findBySprintIdAndParentIsNullOrderBySprintPositionAscIdAsc(target.getId()));
        oldList.removeIf(t -> t.getId().equals(task.getId()));
        if (newList != oldList) newList.removeIf(t -> t.getId().equals(task.getId()));
        if (target != null) {
            int position = 0;
            if (request.afterTaskId() != null) {
                int index = -1;
                for (int i = 0; i < newList.size(); i++) {
                    if (newList.get(i).getId().equals(request.afterTaskId())) { index = i; break; }
                }
                if (index < 0) throw new BadRequestException("afterTaskId must belong to the target sprint.");
                position = index + 1;
            }
            newList.add(position, task);
        }
        task.setSprint(target);
        task.setSprintPosition(null);
        for (int i = 0; i < oldList.size(); i++) oldList.get(i).setSprintPosition((long) (i + 1));
        if (target != null) {
            for (int i = 0; i < newList.size(); i++) {
                newList.get(i).setSprint(target);
                newList.get(i).setSprintPosition((long) (i + 1));
            }
        }
        tasks.flush();
        if (source != null && source.getStatus() == SprintStatus.ACTIVE &&
                (target == null || !source.getId().equals(target.getId()))) {
            scopeWriter.record(source, task, SprintScopeChange.Action.REMOVED, actor);
        }
        if (target != null && target.getStatus() == SprintStatus.ACTIVE &&
                (source == null || !source.getId().equals(target.getId()))) {
            scopeWriter.record(target, task, SprintScopeChange.Action.ADDED, actor);
        }
    }

    /**
     * Close the active sprint (PMP-41, Scrum Master). One transaction: the split, the frozen
     * points and outcomes, the carry-over, the audit entry and the notification all happen or
     * none does, so a failure leaves the sprint ACTIVE.
     */
    @Transactional
    public CompleteSprintResponse complete(User actor, Long sprintId, CompleteSprintRequest request) {
        Sprint sprint = writableSprint(actor, sprintId, ProjectPermission.MANAGE_SPRINTS);
        Project project = sprint.getProject();
        if (sprint.getStatus() != SprintStatus.ACTIVE) throw new SprintConflictException("Only an active sprint can be closed.");
        if (request == null || request.goalAchieved() == null) {
            throw new BadRequestException("Answer whether the Sprint Goal was achieved.");
        }
        Long nextId = request.carryOverSprintId();
        boolean createNew = Boolean.TRUE.equals(request.createSprint());
        if (nextId != null && createNew) throw new BadRequestException("Choose an existing sprint or create a new one, not both.");
        Sprint next = null;
        if (nextId != null) {
            next = findInProject(project.getId(), nextId);
            if (next.getStatus() != SprintStatus.PLANNED) throw new BadRequestException("Unfinished work can only move to a planned sprint.");
        }
        if (LocalDate.now(ZoneOffset.UTC).isBefore(sprint.getEndDate())
                && !Boolean.TRUE.equals(request.confirmEarlyClose())) {
            throw new EarlyCloseConfirmationRequiredException(sprint.getEndDate());
        }
        Split split = split(sprintId);
        // End it before generating follow-up dates: a sprint stops occupying dates on the day it
        // ends, so the new sprint can start today.
        end(sprint, SprintStatus.COMPLETED, split);
        sprint.setGoalAchieved(request.goalAchieved());
        sprint.setOutcomeNote(request.goalNote() == null || request.goalNote().isBlank() ? null : request.goalNote().trim());
        if (createNew) {
            int number = sprints.maxNumber(project.getId()) + 1;
            LocalDate[] dates = nextDates(project, LocalDate.now(ZoneOffset.UTC));
            validateDates(project.getId(), null, dates[0], dates[1]);
            next = sprints.saveAndFlush(new Sprint(project, number, project.getKey() + " Sprint " + number,
                    "", null, dates[0], dates[1]));
        }
        carryOver(sprint, split, next, actor);
        sprints.saveAndFlush(sprint);
        tasks.flush();
        audit.record(project.getWorkspace(), actor, AuditAction.SPRINT_COMPLETED, AuditTarget.sprint(sprint), project,
                AuditChange.of("status", SprintStatus.ACTIVE.name(), SprintStatus.COMPLETED.name()),
                AuditChange.of("completedPoints", null, String.valueOf(sprint.getCompletedPoints())),
                AuditChange.of("unfinishedPoints", null, String.valueOf(sprint.getUnfinishedPoints())),
                AuditChange.of("goalAchieved", null, request.goalAchieved().name()),
                AuditChange.of("carriedOver", null, carriedOverSummary(split, next)));
        events.publishEvent(new SprintCompleted(project, actor, sprint.getName()));
        return new CompleteSprintResponse(response(sprint), split.unfinished().size(),
                next == null ? null : response(next));
    }

    /**
     * Cancel the active sprint because its goal became obsolete (PMP-41): Product Owner, or the
     * Project Manager as an override. No destination choice: unfinished work goes to the top of
     * the backlog. Done items stay linked and the points are still frozen, so reports stay
     * accurate, but velocity reads COMPLETED sprints only, so this one never counts.
     */
    @Transactional
    public CompleteSprintResponse cancel(User actor, Long sprintId, CancelSprintRequest request) {
        Sprint sprint = writableSprint(actor, sprintId, ProjectPermission.CANCEL_SPRINTS);
        Project project = sprint.getProject();
        if (sprint.getStatus() != SprintStatus.ACTIVE) throw new SprintConflictException("Only an active sprint can be cancelled.");
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new BadRequestException("Give a reason for cancelling the sprint.");
        }
        String reason = request.reason().trim();
        Split split = split(sprintId);
        end(sprint, SprintStatus.CANCELLED, split);
        sprint.setCancelReason(reason);
        carryOver(sprint, split, null, actor);
        sprints.saveAndFlush(sprint);
        tasks.flush();
        audit.record(project.getWorkspace(), actor, AuditAction.SPRINT_CANCELLED, AuditTarget.sprint(sprint), project,
                AuditChange.of("status", SprintStatus.ACTIVE.name(), SprintStatus.CANCELLED.name()),
                AuditChange.of("reason", null, reason),
                AuditChange.of("completedPoints", null, String.valueOf(sprint.getCompletedPoints())),
                AuditChange.of("unfinishedPoints", null, String.valueOf(sprint.getUnfinishedPoints())),
                AuditChange.of("carriedOver", null, carriedOverSummary(split, null)));
        events.publishEvent(new SprintCancelled(project, actor, sprint.getName()));
        return new CompleteSprintResponse(response(sprint), split.unfinished().size(), null);
    }

    /** Status, end time and the frozen points: the same for close and cancel. */
    private static void end(Sprint sprint, SprintStatus status, Split split) {
        sprint.setStatus(status);
        sprint.setCompletedAt(Instant.now());
        sprint.setCompletedPoints(split.donePoints());
        sprint.setUnfinishedPoints(split.unfinishedPoints());
    }

    /**
     * Moves the unfinished top-level items of an ended sprint (PMP-41), in sprint order, with
     * their subtasks (they follow the parent's sprint). {@code destination} null means the top of
     * the backlog. Done items stay linked to the ended sprint. Each moved item gets
     * "Carried over ×N" and a history entry; every item gets a frozen outcome row.
     */
    private void carryOver(Sprint source, Split split, Sprint destination, User actor) {
        List<Task> unfinished = split.unfinished();
        if (destination != null) {
            // The carried-over items go first, ahead of what the destination already holds.
            List<Task> order = new ArrayList<>(unfinished);
            for (Task task : tasks.findBySprintIdAndParentIsNullOrderBySprintPositionAscIdAsc(destination.getId())) {
                if (!order.contains(task)) order.add(task);
            }
            for (int i = 0; i < order.size(); i++) {
                order.get(i).setSprint(destination);
                order.get(i).setSprintPosition((long) (i + 1));
            }
        } else if (!unfinished.isEmpty()) {
            // Read the backlog's first rank before the moved items join it.
            long[] ranks = TaskRanks.above(tasks.findMinBacklogRankOfBacklog(source.getProject().getId()),
                    unfinished.size());
            for (int i = 0; i < unfinished.size(); i++) {
                Task task = unfinished.get(i);
                task.setSprint(null);
                task.setSprintPosition(null);
                task.setBacklogRank(ranks[i]);
            }
        }
        String to = destination == null ? null : destination.getName();
        for (Task task : unfinished) {
            task.setCarryOverCount(task.getCarryOverCount() + 1);
            taskHistory.recordCarriedOver(task, source.getName(), to, actor, source.getId());
        }
        List<SprintOutcome> rows = new ArrayList<>();
        for (int i = 0; i < split.all().size(); i++) {
            Task task = split.all().get(i);
            boolean done = !unfinished.contains(task);
            rows.add(new SprintOutcome(source, task.getId(), task.getKey(), task.getEstimate(),
                    done ? SprintOutcome.Result.DONE : SprintOutcome.Result.CARRIED_OVER,
                    done || destination == null ? null : destination.getId(), i));
        }
        outcomes.saveAll(rows);
    }

    /** For the audit entry: "3 items to the backlog", "1 item to SPR Sprint 5". */
    private static String carriedOverSummary(Split split, Sprint destination) {
        int n = split.unfinished().size();
        return n + (n == 1 ? " item" : " items") + " to "
                + (destination == null ? "the backlog" : destination.getName());
    }

    /** The close and cancel dialog (PMP-41): read-only, for anyone who can see the work. */
    @Transactional(readOnly = true)
    public CloseSprintPreviewResponse closePreview(User actor, Long sprintId) {
        Sprint sprint = find(sprintId);
        Long projectId = sprint.getProject().getId();
        readable(actor, projectId);
        if (sprint.getStatus() != SprintStatus.ACTIVE) {
            throw new SprintConflictException("Only an active sprint can be closed or cancelled.");
        }
        Split split = split(sprintId);
        Map<Long, Integer> openSubtasks = new HashMap<>();
        if (!split.unfinished().isEmpty()) {
            for (Object[] row : tasks.findSubtaskProgress(split.unfinished().stream().map(Task::getId).toList())) {
                long total = ((Number) row[1]).longValue();
                long done = ((Number) row[2]).longValue();
                openSubtasks.put(((Number) row[0]).longValue(), (int) (total - done));
            }
        }
        List<CloseSprintPreviewResponse.UnfinishedItem> unfinished = split.unfinished().stream()
                .map(t -> new CloseSprintPreviewResponse.UnfinishedItem(t.getId(), t.getKey(), t.getTitle(),
                        t.getEstimate(), t.getStatus().getName(), openSubtasks.getOrDefault(t.getId(), 0)))
                .toList();
        List<CloseSprintPreviewResponse.PlannedSprint> planned = sprints
                .findByProjectIdAndStatusInOrderByNumberAsc(projectId, List.of(SprintStatus.PLANNED)).stream()
                .map(s -> new CloseSprintPreviewResponse.PlannedSprint(s.getId(), s.getName(), s.getStartDate()))
                .toList();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int daysLeft = (int) Math.max(0, ChronoUnit.DAYS.between(today, sprint.getEndDate()));
        return new CloseSprintPreviewResponse(sprint.getId(), sprint.getName(), sprint.getEndDate(),
                split.done().size(), split.donePoints(), split.unfinished().size(), split.unfinishedPoints(),
                unfinished, today.isBefore(sprint.getEndDate()), daysLeft, planned);
    }

    /**
     * Done versus unfinished top-level items, in sprint order. Done means a DONE-category column;
     * no partial credit, so an unfinished story counts 0 points whatever its subtasks say.
     */
    private record Split(List<Task> all, List<Task> done, List<Task> unfinished) {
        int donePoints() { return points(done); }
        int unfinishedPoints() { return points(unfinished); }
        private static int points(List<Task> items) {
            return items.stream().mapToInt(t -> t.getEstimate() == null ? 0 : t.getEstimate()).sum();
        }
    }

    private Split split(Long sprintId) {
        List<Task> all = tasks.findBySprintIdAndParentIsNullOrderBySprintPositionAscIdAsc(sprintId);
        List<Task> done = new ArrayList<>();
        List<Task> unfinished = new ArrayList<>();
        for (Task task : all) {
            (task.getStatus().getCategory() == BoardColumnCategory.DONE ? done : unfinished).add(task);
        }
        return new Split(List.copyOf(all), List.copyOf(done), List.copyOf(unfinished));
    }

    @Transactional(readOnly = true)
    public CapacitySuggestionResponse suggestion(User actor, Long sprintId) {
        Sprint sprint = find(sprintId);
        readable(actor, sprint.getProject().getId());
        int days = SprintCapacityCalculator.workingDays(sprint.getStartDate(), sprint.getEndDate());
        List<Long> ids = members.findCapacityUserIds(sprint.getProject().getId(), ProjectRole.VIEWER);
        Map<Long, Integer> overrides = new HashMap<>();
        for (SprintAvailability value : availability.findBySprintId(sprintId)) overrides.put(value.getUser().getId(), value.getAvailableDays());
        List<CapacitySuggestionResponse.MemberAvailability> memberDays = ids.stream()
                .map(id -> new CapacitySuggestionResponse.MemberAvailability(id, Math.min(days, overrides.getOrDefault(id, days))))
                .toList();
        int actual = memberDays.stream().mapToInt(CapacitySuggestionResponse.MemberAvailability::availableDays).sum();
        int normal = days * ids.size();
        List<Integer> lastThree = sprints.findTop3ByProjectIdAndStatusOrderByCompletedAtDescIdDesc(
                sprint.getProject().getId(), SprintStatus.COMPLETED).stream()
                .map(s -> s.getCompletedPoints() == null ? 0 : s.getCompletedPoints()).toList();
        Integer suggested = SprintCapacityCalculator.suggest(lastThree, actual, normal);
        Double average = lastThree.isEmpty() ? null : lastThree.stream().mapToInt(Integer::intValue).average().orElse(0);
        return new CapacitySuggestionResponse(suggested, average, normal, actual, days, memberDays);
    }

    @Transactional
    public CapacitySuggestionResponse setAvailability(User actor, Long sprintId, Long memberId, AvailabilityRequest request) {
        Sprint sprint = writableSprint(actor, sprintId, ProjectPermission.MANAGE_SPRINTS);
        Project project = sprint.getProject();
        if (sprint.getStatus().isEnded()) throw new SprintConflictException("An ended sprint can't be changed.");
        if (!members.findCapacityUserIds(project.getId(), ProjectRole.VIEWER).contains(memberId)) {
            throw new BadRequestException("Availability can only be set for non-Viewer project members.");
        }
        int days = SprintCapacityCalculator.workingDays(sprint.getStartDate(), sprint.getEndDate());
        if (request.availableDays() > days) throw new BadRequestException("Available days cannot exceed working days.");
        User user = users.findById(memberId).orElseThrow(() -> new ResourceNotFoundException("User not found."));
        SprintAvailability value = availability.findBySprintIdAndUserId(sprintId, memberId)
                .orElseGet(() -> new SprintAvailability(sprint, user, request.availableDays()));
        value.setAvailableDays(request.availableDays());
        availability.saveAndFlush(value);
        return suggestion(actor, sprintId);
    }

    @Transactional(readOnly = true)
    public PageResponse<SprintScopeChangeResponse> scopeHistory(User actor, Long sprintId, Pageable pageable) {
        Sprint sprint = find(sprintId);
        readable(actor, sprint.getProject().getId());
        Page<SprintScopeChange> page = scopeChanges.findBySprintIdOrderByChangedAtDescIdDesc(sprintId, pageable);
        return PageResponse.from(page.map(SprintScopeChangeResponse::from));
    }

    @Transactional(readOnly = true)
    public List<SprintCommitmentResponse> committedItems(User actor, Long sprintId) {
        Sprint sprint = find(sprintId);
        readable(actor, sprint.getProject().getId());
        return commitments.findBySprintIdOrderByPositionAsc(sprintId).stream()
                .map(SprintCommitmentResponse::from).toList();
    }

    private SprintResponse response(Sprint sprint) {
        List<Task> selected = tasks.findBySprintIdAndParentIsNullOrderBySprintPositionAscIdAsc(sprint.getId());
        int points = selected.stream().mapToInt(t -> t.getEstimate() == null ? 0 : t.getEstimate()).sum();
        List<Long> missing = selected.stream().filter(t -> t.getEstimate() == null).map(Task::getId).toList();
        return SprintResponse.of(sprint, selected.stream().map(Task::getId).toList(), points, missing);
    }

    private Project readable(User actor, Long projectId) {
        Project project = access.require(projectId, actor, ProjectPermission.VIEW_WORK).project();
        ensureScrum(project);
        return project;
    }

    /**
     * Permission checks precede the project lock; authorization is always enforced in the service.
     * {@code MANAGE_SPRINTS} (Scrum Master) for everything except cancel, which needs
     * {@code CANCEL_SPRINTS} (Product Owner, Project Manager).
     */
    private Project writable(User actor, Long projectId, ProjectPermission permission) {
        Project project = access.requireActive(projectId, actor, permission).project();
        ensureScrum(project);
        if (project.getLifecycleStatus() != ProjectLifecycleState.ACTIVE) {
            throw new SprintConflictException("Sprints are read-only when the project is completed or archived.");
        }
        return projects.lockById(projectId).orElseThrow(() -> new ResourceNotFoundException("Project not found."));
    }

    /** Lock the project row before loading the mutable sprint: prevents stale state races. */
    private Sprint writableSprint(User actor, Long sprintId, ProjectPermission permission) {
        Long projectId = sprints.findProjectId(sprintId)
                .orElseThrow(() -> new ResourceNotFoundException("Sprint not found."));
        writable(actor, projectId, permission);
        return findInProject(projectId, sprintId);
    }

    private static void ensureScrum(Project project) {
        if (project.getTemplate() != ProjectTemplate.SCRUM) throw new BadRequestException("Sprints are only available in Scrum projects.");
    }

    private Sprint find(Long sprintId) {
        return sprints.findById(sprintId).orElseThrow(() -> new ResourceNotFoundException("Sprint not found."));
    }

    private Sprint findInProject(Long projectId, Long sprintId) {
        Sprint sprint = find(sprintId);
        if (!sprint.getProject().getId().equals(projectId)) throw new ResourceNotFoundException("Sprint not found.");
        return sprint;
    }

    private static String trim(String text) { return text == null ? "" : text.trim(); }
    private static String requiredName(String name) {
        String value = name.trim();
        if (value.isEmpty()) throw new BadRequestException("Sprint name cannot be blank.");
        if (value.length() > 100) throw new BadRequestException("Sprint name must be at most 100 characters.");
        return value;
    }

    private LocalDate[] createDates(Project project, LocalDate requestedStart, LocalDate requestedEnd) {
        if (requestedStart != null || requestedEnd != null) {
            if (requestedStart == null || requestedEnd == null) throw new BadRequestException("Provide both sprint dates.");
            return new LocalDate[] { requestedStart, requestedEnd };
        }
        return nextDates(project, LocalDate.now(ZoneOffset.UTC));
    }

    /** The first free dates on or after {@code earliestStart}, one sprint length long. */
    private LocalDate[] nextDates(Project project, LocalDate earliestStart) {
        LocalDate start = earliestStart;
        for (Sprint sprint : sprints.findByProjectIdOrderByNumberAsc(project.getId())) {
            LocalDate until = occupiedUntil(sprint);
            if (until != null && until.isAfter(sprint.getStartDate()) && until.isAfter(start)) {
                start = until;
            }
        }
        int days = project.getSprintLengthDays() > 1 ? project.getSprintLengthDays() : 14;
        return new LocalDate[] { start, start.plusDays(days - 1) };
    }

    /**
     * The first day a sprint no longer occupies (exclusive end); null when it has no dates. A
     * planned or active sprint occupies its dates through {@code endDate}. An ended sprint (closed
     * or cancelled) occupies them only up to the day it ended, not including that day, so the next
     * sprint can start the same day, even when it ended on its last planned day. A sprint that
     * ended on its own start day occupies nothing.
     */
    static LocalDate occupiedUntil(Sprint sprint) {
        if (sprint.getStartDate() == null || sprint.getEndDate() == null) return null;
        LocalDate planned = sprint.getEndDate().plusDays(1);
        if (!sprint.getStatus().isEnded() || sprint.getCompletedAt() == null) return planned;
        LocalDate ended = sprint.getCompletedAt().atZone(ZoneOffset.UTC).toLocalDate();
        return ended.isBefore(planned) ? ended : planned;
    }

    private void validateDates(Long projectId, Long exceptId, LocalDate start, LocalDate end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BadRequestException("Sprint end date must be after start date.");
        }
        if (ChronoUnit.DAYS.between(start, end) > 89) {
            throw new BadRequestException("A sprint cannot exceed 90 calendar days.");
        }
        for (Sprint other : sprints.findByProjectIdOrderByNumberAsc(projectId)) {
            if (other.getId().equals(exceptId)) continue;
            LocalDate until = occupiedUntil(other);
            if (until == null || !until.isAfter(other.getStartDate())) continue; // no dates, or occupies nothing
            if (start.isBefore(until) && !end.isBefore(other.getStartDate())) {
                throw new BadRequestException("Sprint dates cannot overlap another sprint.");
            }
        }
    }
}
