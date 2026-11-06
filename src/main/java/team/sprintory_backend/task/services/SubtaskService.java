package team.sprintory_backend.task.services;

import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.common.richtext.RichTextDocument;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.notification.events.TaskAssigned;
import team.sprintory_backend.notification.events.TaskCreated;
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
    private final ApplicationEventPublisher events;
    private final TaskContent content;

    @Transactional
    public TaskResponse create(User user, Long projectId, Long parentId, CreateSubtaskRequest request) {
        Project project = projectAccess.requireActive(projectId, user, ProjectPermission.EDIT_TASKS).project();
        // Check the description and its mentions before anything is written.
        RichTextDocument description = content.parseDescription(request.description());
        Set<Long> mentioned = content.newMentions(project, description, null);
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
                .description(description == null ? null : description.json())
                .labels(TaskContent.normalizeLabels(request.labels()))
                .boardPosition(0).backlogRank(0).build());
        statusHistory.recordCreated(child, user);
        statusHistory.recordSubtaskAdded(parent, child, user);
        events.publishEvent(new TaskCreated(child, user));
        if (assignee != null) {
            events.publishEvent(new TaskAssigned(child, user, assignee));
        }
        content.publishMentions(child, user, mentioned, description);
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
        Project project = projectAccess.requireActive(projectId, user, ProjectPermission.EDIT_TASKS).project();
        projectRepository.lockById(projectId);
        requireParent(projectId, parentId);
        Task child = taskRepository.findByIdAndProjectId(subtaskId, projectId)
                .filter(t -> t.getParent() != null && t.getParent().getId().equals(parentId))
                .orElseThrow(() -> new ResourceNotFoundException("Subtask not found."));
        TaskSnapshot before = TaskSnapshot.of(child);
        // Check a new description and its newly added mentions before anything changes.
        RichTextDocument description = request.clearDescription() ? null
                : content.parseDescription(request.description());
        Set<Long> mentioned = content.newMentions(project, description, child);
        if (request.title() != null) child.setTitle(request.title().trim());
        if (request.statusId() != null) {
            ProjectBoardColumn target = requireColumn(projectId, request.statusId());
            statusHistory.record(child, child.getStatus(), target, user);
            child.setStatus(target);
        }
        // Only a change to another person notifies; clearing or re-setting the same one does not.
        User newAssignee = null;
        if (request.clearAssignee()) {
            child.setAssignee(null);
        } else if (request.assigneeId() != null) {
            Long oldAssigneeId = child.getAssignee() == null ? null : child.getAssignee().getId();
            User assignee = assignee(projectId, request.assigneeId());
            if (!assignee.getId().equals(oldAssigneeId)) newAssignee = assignee;
            child.setAssignee(assignee);
        }
        if (request.clearEstimateMinutes()) child.setEstimateMinutes(null);
        else if (request.estimateMinutes() != null) child.setEstimateMinutes(request.estimateMinutes());
        if (request.clearDescription()) {
            child.setDescription(null);
        } else if (request.description() != null) {
            child.setDescription(description == null ? null : description.json());
        }
        if (request.labels() != null) {
            child.getLabels().clear();
            child.getLabels().addAll(TaskContent.normalizeLabels(request.labels()));
        }
        statusHistory.recordChanges(child, before, TaskSnapshot.of(child), user);
        TaskResponse saved = TaskResponse.from(taskRepository.saveAndFlush(child));
        if (newAssignee != null) {
            events.publishEvent(new TaskAssigned(child, user, newAssignee));
        }
        content.publishMentions(child, user, mentioned, description);
        return saved;
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
