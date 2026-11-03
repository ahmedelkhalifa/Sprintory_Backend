package team.sprintory_backend.notification.services;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.notification.dto.WatchResponse;
import team.sprintory_backend.notification.models.TaskWatcherId;
import team.sprintory_backend.notification.repositories.TaskWatcherRepository;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.user.models.User;

/**
 * Who watches which task (docs/notifications-docs.md §7). Watching by hand needs VIEW_WORK, so
 * Viewers can watch too, and it is allowed in an archived workspace: it is a personal setting,
 * not a change to the project. Automatic watching (create, assign, comment) is done by the
 * notification listener through {@link #autoWatch}.
 */
@Service
@RequiredArgsConstructor
public class TaskWatcherService {

    private final ProjectAccess projectAccess;
    private final TaskRepository taskRepository;
    private final TaskWatcherRepository watcherRepository;

    @Transactional(readOnly = true)
    public WatchResponse isWatching(User user, Long projectId, Long taskId) {
        Task task = requireTask(user, projectId, taskId);
        return new WatchResponse(watcherRepository.existsById(new TaskWatcherId(task.getId(), user.getId())));
    }

    /** Idempotent. */
    @Transactional
    public WatchResponse watch(User user, Long projectId, Long taskId) {
        Task task = requireTask(user, projectId, taskId);
        watcherRepository.insertIfAbsent(task.getId(), user.getId());
        return new WatchResponse(true);
    }

    /** Idempotent. A later assignment or comment makes the person a watcher again. */
    @Transactional
    public WatchResponse unwatch(User user, Long projectId, Long taskId) {
        Task task = requireTask(user, projectId, taskId);
        watcherRepository.deleteById(new TaskWatcherId(task.getId(), user.getId()));
        return new WatchResponse(false);
    }

    /** Starts watching unless already watching. No access check: the caller's action already did it. */
    @Transactional
    public void autoWatch(Task task, User user) {
        watcherRepository.insertIfAbsent(task.getId(), user.getId());
    }

    @Transactional(readOnly = true)
    public List<Long> watcherIdsOf(Long taskId) {
        return watcherRepository.findUserIdsByTaskId(taskId);
    }

    private Task requireTask(User user, Long projectId, Long taskId) {
        projectAccess.require(projectId, user, ProjectPermission.VIEW_WORK);
        return taskRepository.findByIdAndProjectId(taskId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found."));
    }
}