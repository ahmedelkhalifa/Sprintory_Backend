package team.sprintory_backend.task.services;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectBoardColumn;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.repositories.ProjectBoardColumnRepository;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.project.strategy.ProjectBoardStrategies;
import team.sprintory_backend.task.api.TaskListOrder;
import team.sprintory_backend.task.api.TaskSearchFilters;
import team.sprintory_backend.task.dto.CreateTaskRequest;
import team.sprintory_backend.task.dto.MoveTaskRequest;
import team.sprintory_backend.task.dto.ReorderBacklogRequest;
import team.sprintory_backend.task.dto.TaskResponse;
import team.sprintory_backend.task.dto.UpdateTaskRequest;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.models.TaskPriority;
import team.sprintory_backend.task.models.TaskType;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.task.repositories.TaskSpecifications;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.ObjLongConsumer;

/**
 * Tasks inside a project (story 9). Every action goes through {@link ProjectAccess}: reading
 * needs VIEW_WORK, changing needs EDIT_TASKS and an active workspace. Task ids are always looked
 * up inside the requested project, so an id from another project gives 404.
 */
@Service
@RequiredArgsConstructor
public class TaskApiService {

    /** One row: the neighbour right after a given rank. */
    private static final Pageable FIRST = PageRequest.of(0, 1);

    private final ProjectAccess projectAccess;
    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectBoardColumnRepository columnRepository;
    private final UserRepository userRepository;
    private final ProjectBoardStrategies boardStrategies;

    /**
     * Creates the task with the next key ({@code MOB-42}) in the board's first column: To Do for
     * Scrum, where "in the backlog" means "in no sprint", and Backlog for Kanban. It goes last in
     * that column and last in the backlog.
     */
    @Transactional
    public TaskResponse create(User user, Long projectId, CreateTaskRequest request) {
        Project project = projectAccess.requireActive(projectId, user, ProjectPermission.EDIT_TASKS).project();
        User assignee = (request.assigneeId() == null) ? null : requireAssignee(projectId, request.assigneeId());
        ProjectBoardColumn status = firstColumn(projectId);

        // The UPDATE locks the project row first, so the ranks below are read one create at a time.
        projectRepository.incrementTaskNumber(projectId);
        int number = projectRepository.findLastTaskNumber(projectId);
        long boardPosition = TaskRanks.last(taskRepository.findMaxBoardPosition(projectId, status.getId()));
        long backlogRank = TaskRanks.last(taskRepository.findMaxBacklogRank(projectId));

        Task task = taskRepository.save(Task.builder()
                .project(project)
                .number(number)
                .key(project.getKey() + "-" + number)
                .title(request.title().trim())
                .type(request.type() != null ? request.type() : TaskType.TASK)
                .priority(request.priority() != null ? request.priority() : TaskPriority.MEDIUM)
                .status(status)
                .assignee(assignee)
                .estimate(request.estimate())
                .description(normalizeDescription(request.description()))
                .labels(normalizeLabels(request.labels()))
                .boardPosition(boardPosition)
                .backlogRank(backlogRank)
                .build());
        return TaskResponse.from(task);
    }

    /**
     * One page of the project's tasks: every given filter narrows the list, {@code q} matches the
     * title or the key, and {@code order} sorts it as the list, the board or the backlog.
     */
    @Transactional(readOnly = true)
    public PageResponse<TaskResponse> list(User user, Long projectId, TaskSearchFilters filters,
                                           TaskListOrder order, Pageable pageable) {
        projectAccess.require(projectId, user, ProjectPermission.VIEW_WORK);
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), order.sort());
        return PageResponse.from(taskRepository.findAll(TaskSpecifications.matching(projectId, filters), sorted)
                .map(TaskResponse::from));
    }

    @Transactional(readOnly = true)
    public TaskResponse get(User user, Long projectId, Long taskId) {
        projectAccess.require(projectId, user, ProjectPermission.VIEW_WORK);
        return TaskResponse.from(requireTask(projectId, taskId));
    }

    /**
     * Partial update: a null field is unchanged, the {@code clear*} flags clear their field and
     * {@code labels: []} removes all labels. Key, project and sprint cannot change here.
     */
    @Transactional
    public TaskResponse update(User user, Long projectId, Long taskId, UpdateTaskRequest request) {
        projectAccess.requireActive(projectId, user, ProjectPermission.EDIT_TASKS);
        Task task = requireTask(projectId, taskId);

        if (request.title() != null) {
            task.setTitle(request.title().trim());
        }
        if (request.type() != null) {
            task.setType(request.type());
        }
        if (request.priority() != null) {
            task.setPriority(request.priority());
        }
        if (request.statusId() != null) {
            ProjectBoardColumn status = requireColumn(projectId, request.statusId());
            if (!status.getId().equals(task.getStatus().getId())) {
                // Last in the new column. Read the column before the task joins it, under the lock.
                projectRepository.lockById(projectId);
                long position = TaskRanks.last(taskRepository.findMaxBoardPosition(projectId, status.getId()));
                task.setStatus(status);
                task.setBoardPosition(position);
            }
        }
        if (request.clearAssignee()) {
            task.setAssignee(null);
        } else if (request.assigneeId() != null) {
            task.setAssignee(requireAssignee(projectId, request.assigneeId()));
        }
        if (request.clearEstimate()) {
            task.setEstimate(null);
        } else if (request.estimate() != null) {
            task.setEstimate(request.estimate());
        }
        if (request.clearDescription()) {
            task.setDescription(null);
        } else if (request.description() != null) {
            task.setDescription(normalizeDescription(request.description()));
        }
        if (request.labels() != null) {
            task.getLabels().clear();
            task.getLabels().addAll(normalizeLabels(request.labels()));
        }
        // Flush now so the response carries the new updatedAt.
        return TaskResponse.from(taskRepository.saveAndFlush(task));
    }

    /** Soft delete: the row stays, the key is never handed out again, and every read hides it. */
    @Transactional
    public void delete(User user, Long projectId, Long taskId) {
        projectAccess.requireActive(projectId, user, ProjectPermission.EDIT_TASKS);
        Task task = requireTask(projectId, taskId);
        task.setDeletedAt(Instant.now());
        taskRepository.save(task);
    }

    /**
     * A drag on the board: puts the task in {@code statusId}, right after {@code afterTaskId} (or
     * first). Moves inside one column and to another column alike. Needs EDIT_TASKS.
     */
    @Transactional
    public TaskResponse moveOnBoard(User user, Long projectId, Long taskId, MoveTaskRequest request) {
        projectAccess.requireActive(projectId, user, ProjectPermission.EDIT_TASKS);
        // One move at a time per project, so two drags cannot take the same rank.
        projectRepository.lockById(projectId);
        Task task = requireTask(projectId, taskId);
        ProjectBoardColumn target = requireColumn(projectId, request.statusId());
        Task after = requirePlaceAfter(projectId, task, request.afterTaskId());
        if (after != null && !after.getStatus().getId().equals(target.getId())) {
            throw new BadRequestException("The task to place it after must be in the target column.");
        }

        Long previous = (after == null) ? null : after.getBoardPosition();
        Long next = taskRepository.findNextInColumn(projectId, target.getId(), taskId,
                        previous == null ? Long.MIN_VALUE : previous, FIRST).stream()
                .findFirst().map(Task::getBoardPosition).orElse(null);
        Long position = TaskRanks.between(previous, next);

        task.setStatus(target);
        if (position != null) {
            task.setBoardPosition(position);
        } else {
            renumber(taskRepository.findColumnInBoardOrder(projectId, target.getId()), task, after,
                    Task::setBoardPosition);
        }
        return TaskResponse.from(taskRepository.saveAndFlush(task));
    }

    /**
     * A drag in the Scrum product backlog (R13): puts the task right after {@code afterTaskId}, or
     * first. Needs MANAGE_BACKLOG. Kanban has no backlog to reorder: its "Backlog" is a column.
     */
    @Transactional
    public TaskResponse moveInBacklog(User user, Long projectId, Long taskId, ReorderBacklogRequest request) {
        Project project = projectAccess.requireActive(projectId, user, ProjectPermission.MANAGE_BACKLOG).project();
        if (!boardStrategies.forTemplate(project.getTemplate()).backlogEnabled()) {
            throw new BadRequestException("This project has no backlog to reorder; move the task on the board.");
        }
        projectRepository.lockById(projectId);
        Task task = requireTask(projectId, taskId);
        Task after = requirePlaceAfter(projectId, task, request.afterTaskId());

        Long previous = (after == null) ? null : after.getBacklogRank();
        Long next = taskRepository.findNextInBacklog(projectId, taskId,
                        previous == null ? Long.MIN_VALUE : previous, FIRST).stream()
                .findFirst().map(Task::getBacklogRank).orElse(null);
        Long rank = TaskRanks.between(previous, next);

        if (rank != null) {
            task.setBacklogRank(rank);
        } else {
            renumber(taskRepository.findProjectInBacklogOrder(projectId), task, after, Task::setBacklogRank);
        }
        return TaskResponse.from(taskRepository.saveAndFlush(task));
    }

    /**
     * No room between two neighbours: renumbers the whole list {@code GAP} apart, with
     * {@code moving} placed right after {@code after} (or first).
     */
    private static void renumber(List<Task> ordered, Task moving, Task after, ObjLongConsumer<Task> setRank) {
        List<Task> tasks = new ArrayList<>(ordered);
        tasks.remove(moving);
        tasks.add(after == null ? 0 : tasks.indexOf(after) + 1, moving);
        for (int i = 0; i < tasks.size(); i++) {
            setRank.accept(tasks.get(i), (i + 1) * TaskRanks.GAP);
        }
    }

    /** The neighbour to place a task after: another task of this project, or null for "first". */
    private Task requirePlaceAfter(Long projectId, Task moving, Long afterTaskId) {
        if (afterTaskId == null) {
            return null;
        }
        if (afterTaskId.equals(moving.getId())) {
            throw new BadRequestException("A task cannot be placed after itself.");
        }
        return taskRepository.findByIdAndProjectId(afterTaskId, projectId)
                .orElseThrow(() -> new BadRequestException("The task to place it after is not in this project."));
    }

    private ProjectBoardColumn requireColumn(Long projectId, Long statusId) {
        return columnRepository.findByIdAndBoardProjectId(statusId, projectId)
                .orElseThrow(() -> new BadRequestException("The status must be a column of this project's board."));
    }

    /** Missing, deleted and other projects' tasks all give the same 404. */
    private Task requireTask(Long projectId, Long taskId) {
        return taskRepository.findByIdAndProjectId(taskId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found."));
    }

    /** Only members of this project can be assigned (story 9). */
    private User requireAssignee(Long projectId, Long userId) {
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new BadRequestException("The assignee must be a member of this project.");
        }
        return userRepository.getReferenceById(userId);
    }

    /** Every project gets its columns at creation (R06), so a missing one is a server bug. */
    private ProjectBoardColumn firstColumn(Long projectId) {
        return columnRepository.findByBoardProjectIdOrderByPositionAscIdAsc(projectId).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Project " + projectId + " has no board columns."));
    }

    /** Rich text is kept as sent; a blank description means none. */
    private static String normalizeDescription(String description) {
        return (description == null || description.isBlank()) ? null : description;
    }

    /** Trimmed; duplicates that differ only in case collapse into the first spelling. */
    private static Set<String> normalizeLabels(List<String> labels) {
        Set<String> result = new LinkedHashSet<>();
        if (labels == null) {
            return result;
        }
        Set<String> seen = new HashSet<>();
        for (String label : labels) {
            String trimmed = label.trim();
            if (seen.add(trimmed.toLowerCase(Locale.ROOT))) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
