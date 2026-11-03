package team.sprintory_backend.task.services;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectBoardColumn;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.repositories.ProjectBoardColumnRepository;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.task.dto.CreateSubtaskRequest;
import team.sprintory_backend.task.dto.TaskResponse;
import team.sprintory_backend.task.dto.UpdateSubtaskRequest;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.models.TaskPriority;
import team.sprintory_backend.task.models.TaskType;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;

/** One-level subtasks, separate from the top-level board/backlog task APIs. */
@Service
@RequiredArgsConstructor
public class SubtaskService {
    private final ProjectAccess projectAccess;
    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final ProjectBoardColumnRepository columnRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final TaskStatusHistoryService statusHistory;

    @Transactional
    public TaskResponse create(User user, Long projectId, Long parentId, CreateSubtaskRequest request) {
        Project project = projectAccess.requireActive(projectId, user, ProjectPermission.EDIT_TASKS).project();
        projectRepository.incrementTaskNumber(projectId); // serializes with project board edits & tasks
        Task parent = requireParent(projectId, parentId);
        User assignee = assignee(projectId, request.assigneeId());
        ProjectBoardColumn status = request.statusId() == null
                ? firstColumn(projectId) : requireColumn(projectId, request.statusId());
        int number = projectRepository.findLastTaskNumber(projectId);
        Task child = taskRepository.saveAndFlush(Task.builder()
                .project(project).parent(parent)
                .number(number).key(project.getKey() + "-" + number)
                .title(request.title().trim()).type(TaskType.TASK).priority(TaskPriority.MEDIUM)
                .status(status).assignee(assignee).estimateMinutes(request.estimateMinutes())
                .boardPosition(0).backlogRank(0).build());
        return TaskResponse.from(child);
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> list(User user, Long projectId, Long parentId) {
        projectAccess.require(projectId, user, ProjectPermission.VIEW_WORK);
        requireParent(projectId, parentId);
        return taskRepository.findByParentIdOrderByIdAsc(parentId)
                .stream().map(TaskResponse::from).toList();
    }

    @Transactional
    public TaskResponse update(User user, Long projectId, Long parentId, Long subtaskId,
                               UpdateSubtaskRequest request) {
        projectAccess.requireActive(projectId, user, ProjectPermission.EDIT_TASKS);
        projectRepository.lockById(projectId);
        requireParent(projectId, parentId);
        Task child = taskRepository.findByIdAndProjectId(subtaskId, projectId)
                .filter(t -> t.getParent() != null && t.getParent().getId().equals(parentId))
                .orElseThrow(() -> new ResourceNotFoundException("Subtask not found."));
        if (request.title() != null) child.setTitle(request.title().trim());
        if (request.statusId() != null) {
            ProjectBoardColumn target = requireColumn(projectId, request.statusId());
            statusHistory.record(child, child.getStatus(), target, user);
            child.setStatus(target);
        }
        if (request.clearAssignee()) child.setAssignee(null);
        else if (request.assigneeId() != null) child.setAssignee(assignee(projectId, request.assigneeId()));
        if (request.clearEstimateMinutes()) child.setEstimateMinutes(null);
        else if (request.estimateMinutes() != null) child.setEstimateMinutes(request.estimateMinutes());
        return TaskResponse.from(taskRepository.saveAndFlush(child));
    }

    private Task requireParent(Long projectId, Long parentId) {
        Task parent = taskRepository.findByIdAndProjectId(parentId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found."));
        if (parent.getParent() != null) throw new BadRequestException("A subtask cannot have subtasks.");
        return parent;
    }

    private ProjectBoardColumn requireColumn(Long projectId, Long statusId) {
        return columnRepository.findByIdAndBoardProjectId(statusId, projectId)
                .orElseThrow(() -> new BadRequestException("The status must be a column of this project's board."));
    }

    private ProjectBoardColumn firstColumn(Long projectId) {
        return columnRepository.findByBoardProjectIdOrderByPositionAscIdAsc(projectId).stream()
                .findFirst().orElseThrow(() -> new IllegalStateException("Board has no columns."));
    }

    private User assignee(Long projectId, Long userId) {
        if (userId == null) return null;
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new BadRequestException("The assignee must be a member of this project.");
        }
        return userRepository.getReferenceById(userId);
    }
}
