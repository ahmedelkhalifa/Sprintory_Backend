package team.sprintory_backend.task.services;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.notification.events.TaskStatusChanged;
import team.sprintory_backend.project.models.ProjectBoardColumn;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.task.dto.TaskStatusHistoryResponse;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.models.TaskStatusHistory;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.task.repositories.TaskStatusHistoryRepository;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.user.models.User;

@Service
@RequiredArgsConstructor
public class TaskStatusHistoryService {
    private final TaskStatusHistoryRepository historyRepository;
    private final ProjectAccess projectAccess;
    private final TaskRepository taskRepository;
    private final ApplicationEventPublisher events;

    /**
     * Records a move to another column and tells the task's watchers (R27). Every status change
     * comes through here: task update, board move and column delete.
     */
    public void record(Task task, ProjectBoardColumn from, ProjectBoardColumn to, User actor) {
        if (!from.getId().equals(to.getId())) {
            historyRepository.save(new TaskStatusHistory(task, from, to, actor.getId()));
            events.publishEvent(new TaskStatusChanged(task, actor, from.getName(), to.getName()));
        }
    }

    @Transactional(readOnly = true)
    public List<TaskStatusHistoryResponse> list(User user, Long projectId, Long taskId) {
        projectAccess.require(projectId, user, ProjectPermission.VIEW_WORK);
        taskRepository.findByIdAndProjectId(taskId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found."));
        return historyRepository.findByTaskIdOrderByChangedAtAscIdAsc(taskId)
                .stream().map(TaskStatusHistoryResponse::from).toList();
    }
}