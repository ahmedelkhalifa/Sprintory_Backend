package team.sprintory_backend.timelog.services;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.models.ProjectLifecycleState;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.project.services.ProjectAccessResult;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.task.services.TaskStatusHistoryService;
import team.sprintory_backend.timelog.dto.TimeEntryRequest;
import team.sprintory_backend.timelog.dto.TimeEntryResponse;
import team.sprintory_backend.timelog.dto.TimeSummaryResponse;
import team.sprintory_backend.timelog.exceptions.TimeEntryException;
import team.sprintory_backend.timelog.exceptions.TimeEntryConflictException;
import team.sprintory_backend.workspace.services.WorkspaceAccess;
import team.sprintory_backend.timelog.models.TimeEntry;
import team.sprintory_backend.timelog.repositories.TimeEntryRepository;
import team.sprintory_backend.user.models.User;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TimeEntryService {
    private final TimeEntryRepository entries;
    private final TaskRepository tasks;
    private final ProjectRepository projects;
    private final ProjectAccess access;
    private final TaskStatusHistoryService history;
    private final JdbcTemplate jdbc;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TimeEntryResponse create(User actor, Long taskId, TimeEntryRequest request) {
        Task task = writableTask(actor, taskId);
        Long projectId = task.getProject().getId();
        Long workspaceId = task.getProject().getWorkspace().getId();
        LocalDate today = LocalDate.now();
        LocalDate date = request.workDate() == null ? today : request.workDate();
        TimeEntryRules.validate(request.minutes(), date, request.note(), today);
        lockAuthor(workspaceId, actor.getId());
        requireCapacity(workspaceId, actor.getId(), date, request.minutes(), null);
        TimeEntry entry = entries.saveAndFlush(TimeEntry.builder().workspaceId(workspaceId).projectId(projectId)
                .taskId(taskId).userId(actor.getId()).workDate(date).minutes(request.minutes())
                .note(request.note()).build());
        history.recordTimeEntryAdded(task, entry.getId(), snapshot(entry), actor);
        return TimeEntryResponse.from(entry);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TimeEntryResponse update(User actor, Long entryId, TimeEntryRequest request) {
        Task task = writableEntryTask(actor, entryId);
        lockAuthor(task.getProject().getWorkspace().getId(), actor.getId());
        TimeEntry entry = ownedEntry(actor, entryId, task.getId());
        TimeEntryRules.validate(request.minutes(), request.workDate(), request.note(), LocalDate.now());
        boolean noteChanged = !Objects.equals(entry.getNote(), request.note());
        if (entry.getMinutes() == request.minutes() && entry.getWorkDate().equals(request.workDate()) && !noteChanged)
            return TimeEntryResponse.from(entry);
        requireCapacity(entry.getWorkspaceId(), actor.getId(), request.workDate(), request.minutes(), entryId);
        String before = snapshot(entry);
        entry.setMinutes(request.minutes());
        entry.setWorkDate(request.workDate());
        entry.setNote(request.note());
        entries.flush(); // JPA auditing updates updatedAt before the response is built.
        history.recordTimeEntryChanged(task, entryId, before, snapshot(entry), noteChanged, actor);
        return TimeEntryResponse.from(entry);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(User actor, Long entryId) {
        Task task = writableEntryTask(actor, entryId);
        lockAuthor(task.getProject().getWorkspace().getId(), actor.getId());
        TimeEntry entry = ownedEntry(actor, entryId, task.getId());
        entry.setDeletedAt(Instant.now());
        history.recordTimeEntryDeleted(task, entryId, snapshot(entry), actor);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<TimeEntryResponse> mine(User actor, Long taskId, boolean mine) {
        visible(actor, projectId(taskId));
        if (!mine) throw new TimeEntryException("Only your own time entries are available.");
        return entries.findByTaskIdAndUserIdOrderByWorkDateDescIdDesc(taskId, actor.getId()).stream()
                .map(TimeEntryResponse::from).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TimeSummaryResponse summary(User actor, Long taskId) {
        ProjectAccessResult checked = visible(actor, projectId(taskId));
        Object[] sums = entries.totals(taskId).get(0);
        long own = ((Number) sums[0]).longValue(), children = ((Number) sums[1]).longValue();
        boolean canLog = checked.can(ProjectPermission.LOG_TIME)
                && checked.project().getLifecycleStatus() == ProjectLifecycleState.ACTIVE
                && !checked.workspaceMember().getWorkspace().isArchived();
        return new TimeSummaryResponse(own, children, own + children, canLog, LocalDate.now());
    }

    private Task writableTask(User actor, Long taskId) {
        Long projectId = projectId(taskId);
        requireWritableAccess(actor, projectId);
        projects.lockById(projectId).orElseThrow(TimeEntryService::missingTask);
        // Recheck access after the lock; task deletion also takes this lock.
        requireWritableAccess(actor, projectId);
        // Read current database state rather than an entity cached before acquiring the lock.
        String lifecycle = jdbc.queryForObject("select lifecycle_status from projects where id = ?", String.class, projectId);
        if (!ProjectLifecycleState.ACTIVE.name().equals(lifecycle))
            throw TimeEntryConflictException.readOnlyProject();
        return tasks.findByIdAndProjectId(taskId, projectId).orElseThrow(TimeEntryService::missingTask);
    }

    private void requireWritableAccess(User actor, Long projectId) {
        ProjectAccessResult checked = visible(actor, projectId);
        if (!checked.can(ProjectPermission.LOG_TIME))
            throw new AccessDeniedException("Project permission required: LOG_TIME");
        WorkspaceAccess.requireNotArchived(checked.workspaceMember());
    }

    private ProjectAccessResult visible(User actor, Long projectId) {
        ProjectAccessResult checked;
        try { checked = access.requireVisible(projectId, actor); }
        catch (ResourceNotFoundException e) { throw missingTask(); }
        if (checked.projectMember() == null) throw missingTask();
        if (!checked.can(ProjectPermission.VIEW_WORK)) throw new AccessDeniedException("Project permission required: VIEW_WORK");
        return checked;
    }

    /** One transaction-scoped lock per workspace/author, even when changing dates or crossing projects.
     * READ COMMITTED ensures the sum after waiting observes the preceding committed write.
     * Hash collisions only serialize unrelated authors; they cannot weaken the limit.
     */
    private void lockAuthor(Long workspaceId, Long userId) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(cast(? as text), 0))",
                (ResultSetExtractor<Void>) rs -> { rs.next(); return null; }, "sprintory:time:" + workspaceId + ":" + userId);
    }

    private void requireCapacity(Long workspaceId, Long userId, LocalDate date, int minutes, Long excludedId) {
        TimeEntryRules.requireCapacity(entries.dailyMinutes(workspaceId, userId, date, excludedId == null ? 0L : excludedId), minutes);
    }

    private Long projectId(Long taskId) {
        return tasks.findProjectIdById(taskId).orElseThrow(TimeEntryService::missingTask);
    }

    private Task writableEntryTask(User actor, Long entryId) {
        try {
            Long taskId = entries.findTaskId(entryId).orElseThrow(TimeEntryService::missingEntry);
            return writableTask(actor, taskId);
        } catch (ResourceNotFoundException ignored) {
            throw missingEntry();
        }
    }

    private TimeEntry ownedEntry(User actor, Long entryId, Long taskId) {
        TimeEntry entry = entries.findById(entryId).filter(e -> e.getTaskId().equals(taskId))
                .orElseThrow(TimeEntryService::missingEntry);
        if (!entry.getUserId().equals(actor.getId())) throw new AccessDeniedException("Only the author may change a time entry.");
        return entry;
    }

    private static String snapshot(TimeEntry entry) { return TimeEntryRules.snapshot(entry.getMinutes(), entry.getWorkDate()); }
    private static ResourceNotFoundException missingTask() { return new ResourceNotFoundException("Task not found."); }
    private static ResourceNotFoundException missingEntry() { return new ResourceNotFoundException("Time entry not found."); }
}
