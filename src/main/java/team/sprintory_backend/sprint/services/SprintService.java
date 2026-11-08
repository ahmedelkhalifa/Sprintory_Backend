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
import team.sprintory_backend.notification.events.SprintCompleted;
import team.sprintory_backend.project.models.Project;
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
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        Project locked = projects.lockById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found."));
        locked.setSprintLengthDays(request.days());
        return projects.saveAndFlush(locked).getSprintLengthDays();
    }

    @Transactional(readOnly = true)
    public List<SprintResponse> list(User actor, Long projectId) {
        readable(actor, projectId);
        return sprints.findByProjectIdAndStatusNotOrderByNumberAsc(projectId, SprintStatus.COMPLETED)
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
        Project project = writable(actor, projectId);
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
        Sprint sprint = find(sprintId);
        writable(actor, sprint.getProject().getId());
        if (sprint.getStatus() == SprintStatus.COMPLETED) throw new SprintConflictException("A completed sprint can't be changed.");
        if (request.name() != null) sprint.setName(requiredName(request.name()));
        if (request.goal() != null) sprint.setGoal(trim(request.goal()));
        if (Boolean.TRUE.equals(request.clearCapacity())) {
            if (request.capacity() != null) throw new BadRequestException("Cannot set and clear capacity together.");
            sprint.setCapacity(null);
        } else if (request.capacity() != null) sprint.setCapacity(request.capacity());
        if (request.startDate() != null || request.endDate() != null) {
            LocalDate from = request.startDate() == null ? sprint.getStartDate() : request.startDate();
            LocalDate to = request.endDate() == null ? sprint.getEndDate() : request.endDate();
            validateDates(sprint.getProject().getId(), sprint.getId(), from, to);
            sprint.setStartDate(from);
            sprint.setEndDate(to);
        }
        return response(sprints.saveAndFlush(sprint));
    }

    @Transactional
    public void delete(User actor, Long sprintId) {
        Sprint sprint = find(sprintId);
        writable(actor, sprint.getProject().getId());
        if (sprint.getStatus() != SprintStatus.PLANNED) throw new SprintConflictException("Only a planned sprint can be deleted.");
        for (Task task : tasks.findBySprintIdAndParentIsNullOrderBySprintPositionAscIdAsc(sprintId)) {
            task.setSprint(null);
            task.setSprintPosition(null);
        }
        // Tasks keep their original backlog_rank; the database owns the assignment.
        tasks.flush();
        sprints.delete(sprint);
    }

    @Transactional
    public SprintResponse start(User actor, Long sprintId, StartSprintRequest request) {
        Sprint sprint = find(sprintId);
        Project project = writable(actor, sprint.getProject().getId());
        if (sprint.getStatus() != SprintStatus.PLANNED) throw new SprintConflictException("Only a planned sprint can be started.");
        if (sprints.findFirstByProjectIdAndStatus(project.getId(), SprintStatus.ACTIVE).isPresent()) {
            throw new SprintAlreadyActiveException();
        }
        String goal = request != null && request.goal() != null ? trim(request.goal()) : sprint.getGoal();
        if (goal == null || goal.isBlank()) throw new BadRequestException("Add a Sprint Goal first.");
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
        writable(actor, projectId);
        Task task = tasks.findByIdAndProjectId(request.taskId(), projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found."));
        if (task.getParent() != null) throw new BadRequestException("Subtasks follow their parent and cannot be planned separately.");
        Sprint source = task.getSprint();
        Sprint target = request.sprintId() == null ? null : findInProject(projectId, request.sprintId());
        if (source != null && source.getStatus() == SprintStatus.COMPLETED) {
            throw new SprintConflictException("Tasks from a completed sprint cannot be moved.");
        }
        if (target != null && target.getStatus() == SprintStatus.COMPLETED) {
            throw new SprintConflictException("A completed sprint can't accept work.");
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
            recordChange(source, task, SprintScopeChange.Action.REMOVED, actor);
        }
        if (target != null && target.getStatus() == SprintStatus.ACTIVE &&
                (source == null || !source.getId().equals(target.getId()))) {
            recordChange(target, task, SprintScopeChange.Action.ADDED, actor);
        }
    }

    @Transactional
    public CompleteSprintResponse complete(User actor, Long sprintId, CompleteSprintRequest request) {
        Sprint sprint = find(sprintId);
        Project project = writable(actor, sprint.getProject().getId());
        if (sprint.getStatus() != SprintStatus.ACTIVE) throw new SprintConflictException("Only an active sprint can be completed.");
        Long nextId = request == null ? null : request.carryOverSprintId();
        boolean createNew = request != null && Boolean.TRUE.equals(request.createSprint());
        if (nextId != null && createNew) throw new BadRequestException("Choose an existing sprint or create a new one, not both.");
        Sprint next = null;
        if (nextId != null) {
            next = findInProject(project.getId(), nextId);
            if (next.getStatus() != SprintStatus.PLANNED) throw new BadRequestException("Unfinished work can only move to a planned sprint.");
        }
        List<Task> selected = tasks.findBySprintIdAndParentIsNullOrderBySprintPositionAscIdAsc(sprintId);
        List<Task> unfinished = new ArrayList<>();
        int completedPoints = 0;
        for (Task task : selected) {
            if (task.getStatus().getCategory() == team.sprintory_backend.project.models.BoardColumnCategory.DONE) {
                completedPoints += task.getEstimate() == null ? 0 : task.getEstimate();
            } else unfinished.add(task);
        }
        if (createNew) {
            int number = sprints.maxNumber(project.getId()) + 1;
            LocalDate[] dates = nextDates(project, sprint.getEndDate());
            validateDates(project.getId(), null, dates[0], dates[1]);
            next = sprints.saveAndFlush(new Sprint(project, number, project.getKey() + " Sprint " + number,
                    "", null, dates[0], dates[1]));
        }
        // Preserve the completed sprint's DONE tasks; move unfinished items to the front of next.
        if (next != null) {
            List<Task> destination = new ArrayList<>(tasks.findBySprintIdAndParentIsNullOrderBySprintPositionAscIdAsc(next.getId()));
            destination.removeAll(unfinished);
            unfinished.addAll(destination);
            for (int i = 0; i < unfinished.size(); i++) {
                Task task = unfinished.get(i);
                task.setSprint(next);
                task.setSprintPosition((long) (i + 1));
            }
        } else {
            for (Task task : unfinished) { task.setSprint(null); task.setSprintPosition(null); }
        }
        sprint.setStatus(SprintStatus.COMPLETED);
        sprint.setCompletedAt(Instant.now());
        sprint.setCompletedPoints(completedPoints);
        sprints.saveAndFlush(sprint);
        tasks.flush();
        events.publishEvent(new SprintCompleted(project, actor, sprint.getName()));
        return new CompleteSprintResponse(response(sprint),
                (int) selected.stream().filter(t -> t.getStatus().getCategory() !=
                        team.sprintory_backend.project.models.BoardColumnCategory.DONE).count(),
                next == null ? null : response(next));
    }

    @Transactional(readOnly = true)
    public CapacitySuggestionResponse suggestion(User actor, Long sprintId) {
        Sprint sprint = find(sprintId);
        readable(actor, sprint.getProject().getId());
        int days = SprintCapacityCalculator.workingDays(sprint.getStartDate(), sprint.getEndDate());
        List<Long> ids = members.findUserIdsByProjectId(sprint.getProject().getId());
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
        Sprint sprint = find(sprintId);
        Project project = writable(actor, sprint.getProject().getId());
        if (sprint.getStatus() == SprintStatus.COMPLETED) throw new SprintConflictException("A completed sprint can't be changed.");
        if (!members.existsByProjectIdAndUserId(project.getId(), memberId)) {
            throw new BadRequestException("Availability can only be set for project members.");
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

    private void recordChange(Sprint sprint, Task task, SprintScopeChange.Action action, User actor) {
        scopeChanges.save(new SprintScopeChange(sprint, task.getId(), task.getKey(), action, actor.getId()));
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

    /** Permission checks precede the project lock; authorization is always enforced in the service. */
    private Project writable(User actor, Long projectId) {
        Project project = access.requireActive(projectId, actor, ProjectPermission.MANAGE_SPRINTS).project();
        ensureScrum(project);
        if (project.getLifecycleStatus() != ProjectLifecycleState.ACTIVE) {
            throw new SprintConflictException("Sprints are read-only when the project is completed or archived.");
        }
        return projects.lockById(projectId).orElseThrow(() -> new ResourceNotFoundException("Project not found."));
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
        return nextDates(project, LocalDate.now(ZoneOffset.UTC).minusDays(1));
    }

    private LocalDate[] nextDates(Project project, LocalDate notBefore) {
        LocalDate start = notBefore.plusDays(1);
        for (Sprint sprint : sprints.findByProjectIdOrderByNumberAsc(project.getId())) {
            if (sprint.getEndDate() != null && !sprint.getEndDate().isBefore(start)) {
                start = sprint.getEndDate().plusDays(1);
            }
        }
        int days = project.getSprintLengthDays() > 1 ? project.getSprintLengthDays() : 14;
        return new LocalDate[] { start, start.plusDays(days - 1) };
    }

    private void validateDates(Long projectId, Long exceptId, LocalDate start, LocalDate end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BadRequestException("Sprint end date must be after start date.");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(start, end) > 89) {
            throw new BadRequestException("A sprint cannot exceed 90 calendar days.");
        }
        for (Sprint other : sprints.findByProjectIdOrderByNumberAsc(projectId)) {
            if (other.getId().equals(exceptId) || other.getStartDate() == null || other.getEndDate() == null) continue;
            if (!start.isAfter(other.getEndDate()) && !end.isBefore(other.getStartDate())) {
                throw new BadRequestException("Sprint dates cannot overlap another sprint.");
            }
        }
    }
}
