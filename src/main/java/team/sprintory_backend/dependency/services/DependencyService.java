package team.sprintory_backend.dependency.services;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.dependency.dto.DependencyCreateRequest;
import team.sprintory_backend.dependency.dto.DependencyLinkResponse;
import team.sprintory_backend.dependency.dto.DependencyResponse;
import team.sprintory_backend.dependency.dto.DependencyTaskSummary;
import team.sprintory_backend.dependency.dto.TaskDependenciesResponse;
import team.sprintory_backend.dependency.exceptions.DependencyConflict;
import team.sprintory_backend.dependency.models.TaskDependency;
import team.sprintory_backend.dependency.repositories.DependencyRepository;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ErrorCode;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.project.models.ProjectLifecycleState;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.project.services.ProjectAccessResult;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.task.services.TaskStatusHistoryService;
import team.sprintory_backend.user.models.User;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DependencyService {
    private final DependencyRepository dependencies;
    private final TaskRepository tasks;
    private final ProjectRepository projects;
    private final ProjectAccess access;
    private final TaskStatusHistoryService history;
    private final EntityManager entityManager;
    private final UnblockedTracker unblocked;

    @Transactional(readOnly = true)
    public TaskDependenciesResponse get(User user, Long taskId) {
        Task task = tasks.findById(taskId).orElseThrow(DependencyService::missingTask);
        ProjectAccessResult viewer = membership(user, task.getProject().getId());
        boolean canManage = task.getParent() == null && viewer.can(ProjectPermission.DEPENDENCY_MANAGE)
                && viewer.project().getLifecycleStatus() == ProjectLifecycleState.ACTIVE
                && !viewer.workspaceMember().getWorkspace().isArchived();
        List<DependencyLinkResponse> incoming = new ArrayList<>(), outgoing = new ArrayList<>();
        for (Object[] row : dependencies.liveLinks(task.getProject().getId(), taskId)) {
            Long id = ((Number) row[0]).longValue();
            DependencyTaskSummary blocker = DependencyTaskSummary.fromRow(row, 1), blocked = DependencyTaskSummary.fromRow(row, 6);
            if (blocked.id().equals(taskId)) incoming.add(new DependencyLinkResponse(id, blocker));
            else outgoing.add(new DependencyLinkResponse(id, blocked));
        }
        Comparator<DependencyLinkResponse> byKey = Comparator.comparing((DependencyLinkResponse l) -> l.task().key()).thenComparing(l -> l.task().id());
        incoming.sort(byKey); outgoing.sort(byKey);
        List<DependencyTaskSummary> open = incoming.stream().map(DependencyLinkResponse::task).filter(t -> t.statusCategory()!=BoardColumnCategory.DONE).toList();
        return new TaskDependenciesResponse(!open.isEmpty(), List.copyOf(incoming), List.copyOf(outgoing), open, canManage);
    }

    @Transactional
    public DependencyResponse add(User user, Long taskId, DependencyCreateRequest request) {
        Task anchor = visibleTask(user, taskId);
        Long projectId = anchor.getProject().getId();
        lockWritable(user, projectId);
        // The initial lookup may be cached while we waited for the project lock.
        entityManager.flush();
        entityManager.clear();
        writable(user, projectId);
        Long a = request.blockerId(), b = request.blockedId();
        if (a == null || b == null || a <= 0 || b <= 0) throw new BadRequestException("Both task IDs must be positive.");
        if (!taskId.equals(a) && !taskId.equals(b)) throw new BadRequestException("The path task must be one endpoint.");
        if (a.equals(b)) throw new BadRequestException("A task cannot block itself.");
        Task blocker = endpoint(projectId, a), blocked = endpoint(projectId, b);
        if (dependencies.existsByBlockerIdAndBlockedId(a,b)) {
            throw conflict(ErrorCode.DEPENDENCY_ALREADY_EXISTS, "This dependency already exists.");
        }
        Map<Long,List<Long>> graph = new HashMap<>();
        for (TaskDependency d : dependencies.findByProjectIdOrderByIdAsc(projectId))
            graph.computeIfAbsent(d.getBlockerId(), k -> new ArrayList<>()).add(d.getBlockedId());
        List<Long> path = DependencyGraph.cyclePath(graph,a,b);
        if (!path.isEmpty()) {
            Map<Long,String> keys = new HashMap<>();
            for (Object[] row : dependencies.keysIncludingDeleted(projectId, new HashSet<>(path)))
                keys.put(((Number) row[0]).longValue(), (String) row[1]);
            List<String> cycle = path.stream().map(keys::get).toList();
            throw new DependencyConflict(ErrorCode.DEPENDENCY_CYCLE, "This would create a cycle: " + String.join(" -> ", cycle) + ".", cycle, null);
        }
        TaskDependency link = dependencies.saveAndFlush(new TaskDependency(projectId,a,b,user.getId()));
        history.recordDependencyAdded(blocker, blocked, user);
        return new DependencyResponse(link.getId(),DependencyTaskSummary.from(blocker),DependencyTaskSummary.from(blocked));
    }

    @Transactional
    public void remove(User user, Long id) {
        TaskDependency link = dependencies.findById(id).orElseThrow(DependencyService::missingLink);
        Long projectId = link.getProjectId();
        try { lockWritable(user, projectId); }
        catch (ResourceNotFoundException ignored) { throw missingLink(); }
        entityManager.flush();
        entityManager.clear();
        try { writable(user, projectId); }
        catch (ResourceNotFoundException ignored) { throw missingLink(); }
        link = dependencies.findById(id).orElseThrow(DependencyService::missingLink);
        // A known link ID can also be removed while either endpoint is soft-deleted.
        // The keys are read in the authorized project, including tombstones, for both histories.
        Map<Long,String> keys = new HashMap<>();
        for (Object[] row : dependencies.keysIncludingDeleted(projectId,List.of(link.getBlockerId(), link.getBlockedId())))
            keys.put(((Number) row[0]).longValue(), (String) row[1]);
        var before = unblocked.before(projectId);
        dependencies.delete(link);
        history.recordDependencyRemoved(link.getBlockerId(), keys.get(link.getBlockerId()),
                link.getBlockedId(), keys.get(link.getBlockedId()), user);
        unblocked.after(projectId,before,user);
    }

    private Task visibleTask(User user, Long id) {
        Task task = tasks.findById(id).orElseThrow(DependencyService::missingTask);
        membership(user, task.getProject().getId());
        return task;
    }
    private ProjectAccessResult membership(User user, Long projectId) {
        return requireMemberAccess(user, projectId, ProjectPermission.VIEW_WORK, false);
    }

    /** PMP-45 requires 404 for every non-project-member, including a workspace admin. */
    private ProjectAccessResult requireMemberAccess(User user, Long projectId,
                                                    ProjectPermission permission, boolean write) {
        try {
            ProjectAccessResult result = write ? access.requireActive(projectId, user, permission)
                    : access.require(projectId, user, permission);
            if (result.projectMember() == null) throw missingTask();
            return result;
        } catch (ResourceNotFoundException ignored) {
            throw missingTask();
        } catch (AccessDeniedException denied) {
            // The shared access helper deliberately returns 403 for a visible workspace admin.
            // For dependencies, mask that one case; project members still get 403 as usual.
            ProjectAccessResult visible;
            try { visible = access.requireVisible(projectId, user); }
            catch (ResourceNotFoundException ignored) { throw missingTask(); }
            if (visible.projectMember() == null) throw missingTask();
            throw denied;
        }
    }

    private void lockWritable(User user, Long projectId) {
        writable(user, projectId);
        projects.lockById(projectId).orElseThrow(DependencyService::missingTask);
    }

    private void writable(User user, Long projectId) {
        ProjectAccessResult result = requireMemberAccess(user, projectId, ProjectPermission.DEPENDENCY_MANAGE, true);
        // ProjectAccess.requireActive on this base checks workspace archive, not project lifecycle.
        if (result.project().getLifecycleStatus() != ProjectLifecycleState.ACTIVE)
            throw conflict(ErrorCode.CONFLICT, "Dependencies are read-only when the project is completed or archived.");
    }
    private Task endpoint(Long projectId, Long id) {
        Task task = tasks.findByIdAndProjectId(id,projectId).orElseThrow(() -> new BadRequestException("Both tasks must be live tasks in this project."));
        if (task.getParent()!=null) throw new BadRequestException("Subtasks cannot have dependencies.");
        return task;
    }
    private static DependencyConflict conflict(ErrorCode code, String message) { return new DependencyConflict(code,message,null,null); }
    private static ResourceNotFoundException missingTask() { return new ResourceNotFoundException("Task not found."); }
    private static ResourceNotFoundException missingLink() { return new ResourceNotFoundException("Dependency not found."); }
}
