package team.sprintory_backend.dependency;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.dependency.DependencyDtos.*;
import team.sprintory_backend.exceptionHandler.*;
import team.sprintory_backend.project.models.*;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.*;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.task.services.TaskStatusHistoryService;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.services.WorkspaceAccess;
import jakarta.persistence.EntityManager;
import java.util.*;

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
    public Response get(User user, Long taskId) {
        Task task = visibleTask(user, taskId);
        List<Link> incoming = new ArrayList<>(), outgoing = new ArrayList<>();
        for (Object[] row : dependencies.liveLinks(task.getProject().getId(), taskId)) {
            Long id = ((Number) row[0]).longValue();
            TaskSummary blocker = TaskSummary.fromRow(row, 1), blocked = TaskSummary.fromRow(row, 6);
            if (blocked.id().equals(taskId)) incoming.add(new Link(id, blocker));
            else outgoing.add(new Link(id, blocked));
        }
        Comparator<Link> byKey = Comparator.comparing((Link l) -> l.task().key()).thenComparing(l -> l.task().id());
        incoming.sort(byKey); outgoing.sort(byKey);
        List<TaskSummary> open = incoming.stream().map(Link::task).filter(t -> t.statusCategory()!=BoardColumnCategory.DONE).toList();
        return new Response(!open.isEmpty(), List.copyOf(incoming), List.copyOf(outgoing), open);
    }

    @Transactional
    public Edge add(User user, Long taskId, CreateRequest request) {
        Task anchor = visibleTask(user, taskId);
        Long projectId = anchor.getProject().getId();
        lockWritable(user, projectId);
        // The initial lookup may be cached while we waited for the project lock.
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
        history.recordDependency(blocker, blocked, true, true, user);
        history.recordDependency(blocked, blocker, false, true, user);
        return new Edge(link.getId(),TaskSummary.from(blocker),TaskSummary.from(blocked));
    }

    @Transactional
    public void remove(User user, Long id) {
        TaskDependency link = dependencies.findById(id).orElseThrow(DependencyService::missingLink);
        Long projectId = link.getProjectId();
        lockWritable(user, projectId);
        entityManager.clear();
        writable(user, projectId);
        link = dependencies.findById(id).orElseThrow(DependencyService::missingLink);
        // Hidden links are retained until restoration; do not expose deleted task data.
        Task blocker = tasks.findByIdAndProjectId(link.getBlockerId(),projectId).orElseThrow(DependencyService::missingLink);
        Task blocked = tasks.findByIdAndProjectId(link.getBlockedId(),projectId).orElseThrow(DependencyService::missingLink);
        var before = unblocked.before(projectId);
        dependencies.delete(link);
        history.recordDependency(blocker, blocked, true, false, user);
        history.recordDependency(blocked, blocker, false, false, user);
        unblocked.after(projectId,before,user);
    }

    private Task visibleTask(User user, Long id) {
        Task task = tasks.findById(id).orElseThrow(DependencyService::missingTask);
        membership(user, task.getProject().getId());
        return task;
    }
    private ProjectAccessResult membership(User user, Long projectId) {
        ProjectAccessResult result = access.requireVisible(projectId,user);
        if (result.projectMember()==null || !result.can(ProjectPermission.VIEW_WORK)) throw missingTask();
        return result;
    }
    private void lockWritable(User user, Long projectId) {
        writable(user,projectId);
        projects.lockById(projectId).orElseThrow(DependencyService::missingTask);
    }
    private void writable(User user, Long projectId) {
        ProjectAccessResult result = membership(user,projectId);
        if (!result.can(ProjectPermission.DEPENDENCY_MANAGE)) throw new AccessDeniedException("Dependency management permission required.");
        WorkspaceAccess.requireNotArchived(result.workspaceMember());
        if (result.project().getLifecycleStatus()!=ProjectLifecycleState.ACTIVE)
            throw conflict(ErrorCode.CONFLICT,"Dependencies are read-only when the project is completed or archived.");
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
