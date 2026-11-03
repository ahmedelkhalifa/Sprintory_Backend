package team.sprintory_backend.project.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.dto.*;
import team.sprintory_backend.project.models.*;
import team.sprintory_backend.project.repositories.*;
import team.sprintory_backend.project.strategy.*;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.task.services.TaskStatusHistoryService;
import team.sprintory_backend.user.models.User;

/** PMP-125: transactional board configuration; every mutation requires CONFIGURE_BOARD. */
@Service
@RequiredArgsConstructor
public class ProjectBoardService {
    private final ProjectBoardRepository boardRepository;
    private final ProjectBoardColumnRepository columnRepository;
    private final ProjectBoardStrategies strategies;
    private final ProjectAccess projectAccess;
    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final TaskStatusHistoryService history;

    @Transactional(propagation = Propagation.MANDATORY)
    public void initialize(Project project) {
        ProjectBoardTemplateStrategy strategy = strategies.forTemplate(project.getTemplate());
        ProjectBoard board = boardRepository.save(new ProjectBoard(project));
        List<ProjectBoardColumn> columns = new ArrayList<>();
        int position = 0;
        for (String name : strategy.defaultColumns()) {
            columns.add(new ProjectBoardColumn(board, name, position++));
        }
        columnRepository.saveAll(columns);
    }

    /** Workspace Admin can configure without VIEW_WORK; all other non-members see 404. */
    @Transactional(readOnly = true)
    public ProjectBoardResponse get(User user, Long projectId) {
        ProjectAccessResult access = projectAccess.requireVisible(projectId, user);
        if (!access.can(ProjectPermission.VIEW_WORK) && !access.can(ProjectPermission.CONFIGURE_BOARD)) {
            throw new AccessDeniedException("Project board permission required.");
        }
        Project project = access.project();
        if (!boardRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Project board not found.");
        }
        ProjectBoardTemplateStrategy strategy = strategies.forTemplate(project.getTemplate());
        List<ProjectBoardColumnResponse> columns = active(projectId).stream()
                .map(c -> ProjectBoardColumnResponse.from(c,
                        taskRepository.countByProjectIdAndStatusIdAndParentIsNull(projectId, c.getId()),
                        taskRepository.countByProjectIdAndStatusIdAndParentIsNotNull(projectId, c.getId())))
                .toList();
        return new ProjectBoardResponse(projectId, project.getTemplate(), strategy.backlogEnabled(),
                strategy.sprintsEnabled(), strategy.wipLimitsEnabled(), null, columns);
    }

    @Transactional
    public ProjectBoardResponse add(User user, Long projectId, CreateBoardColumnRequest request) {
        Project project = lockedProject(user, projectId);
        List<ProjectBoardColumn> columns = active(projectId);
        if (columns.size() >= 10) throw new BadRequestException("A board can have at most 10 columns.");
        String name = validateName(request.name(), null, columns);
        int insertAt = columns.size();
        if (request.afterColumnId() != null) {
            insertAt = indexOf(columns, request.afterColumnId()) + 1;
        }
        if (project.getTemplate() == ProjectTemplate.KANBAN && insertAt == 0) {
            throw new BadRequestException("Kanban Backlog must remain the first column.");
        }
        if (request.category() == BoardColumnCategory.DONE && insertAt == 0 && project.getTemplate() == ProjectTemplate.KANBAN) {
            throw new BadRequestException("Backlog must be TODO.");
        }
        ProjectBoardColumn added = new ProjectBoardColumn(boardRepository.getReferenceById(projectId), name,
                columns.size());
        added.setCategory(request.category());
        columnRepository.saveAndFlush(added);
        columns.add(insertAt, added);
        reorderPersisted(columns);
        return get(user, projectId);
    }

    @Transactional
    public ProjectBoardResponse update(User user, Long projectId, Long columnId,
                                       UpdateBoardColumnRequest request) {
        Project project = lockedProject(user, projectId);
        List<ProjectBoardColumn> columns = active(projectId);
        ProjectBoardColumn column = columns.get(indexOf(columns, columnId));
        boolean backlog = isKanbanBacklog(project, columns, column);
        if (request.name() != null) {
            column.setName(validateName(request.name(), columnId, columns));
        }
        if (request.category() != null && request.category() != column.getCategory()) {
            if (backlog) throw new BadRequestException("Kanban Backlog category cannot be changed.");
            if (column.getCategory() == BoardColumnCategory.DONE
                    && countCategory(columns, BoardColumnCategory.DONE) <= 1) {
                throw new BadRequestException("At least one DONE column must remain.");
            }
            if (column.getCategory() == BoardColumnCategory.TODO
                    && countCategory(columns, BoardColumnCategory.TODO) <= 1) {
                throw new BadRequestException("At least one TODO column must remain.");
            }
            if (request.category() == BoardColumnCategory.DONE && column.getWipLimit() != null
                    && !Boolean.TRUE.equals(request.clearWipLimit())) {
                throw new BadRequestException("DONE columns cannot have WIP limits; clear the limit first.");
            }
            column.setCategory(request.category());
        }
        if (Boolean.TRUE.equals(request.clearWipLimit()) && request.wipLimit() != null) {
            throw new BadRequestException("Cannot set and clear a WIP limit at the same time.");
        }
        if (request.wipLimit() != null) {
            if (project.getTemplate() != ProjectTemplate.KANBAN || backlog
                    || column.getCategory() == BoardColumnCategory.DONE) {
                throw new BadRequestException("WIP limits are only supported on non-Backlog, non-DONE Kanban columns.");
            }
            column.setWipLimit(request.wipLimit());
        } else if (Boolean.TRUE.equals(request.clearWipLimit())) {
            column.setWipLimit(null);
        }
        columnRepository.saveAndFlush(column);
        return get(user, projectId);
    }

    @Transactional
    public ProjectBoardResponse reorder(User user, Long projectId, ReorderBoardColumnsRequest request) {
        Project project = lockedProject(user, projectId);
        List<ProjectBoardColumn> columns = active(projectId);
        List<Long> wanted = request.columnIds();
        if (wanted.size() != columns.size() || new HashSet<>(wanted).size() != columns.size()
                || !wanted.containsAll(columns.stream().map(ProjectBoardColumn::getId).toList())) {
            throw new BadRequestException("Supply every active column ID exactly once.");
        }
        if (project.getTemplate() == ProjectTemplate.KANBAN
                && !wanted.get(0).equals(columns.get(0).getId())) {
            throw new BadRequestException("Kanban Backlog must remain the first column.");
        }
        List<ProjectBoardColumn> ordered = wanted.stream()
                .map(id -> columns.get(indexOf(columns, id))).toList();
        reorderPersisted(ordered);
        return get(user, projectId);
    }

    /** Soft-delete the column. Every moved task receives a durable status history event. */
    @Transactional
    public void delete(User user, Long projectId, Long columnId, DeleteBoardColumnRequest request) {
        Project project = lockedProject(user, projectId);
        List<ProjectBoardColumn> columns = active(projectId);
        if (columns.size() <= 2) throw new BadRequestException("A board must have at least 2 columns.");
        ProjectBoardColumn column = columns.get(indexOf(columns, columnId));
        if (isKanbanBacklog(project, columns, column)) {
            throw new BadRequestException("Kanban Backlog cannot be deleted.");
        }
        if (column.getCategory() == BoardColumnCategory.DONE
                && countCategory(columns, BoardColumnCategory.DONE) <= 1) {
            throw new BadRequestException("At least one DONE column must remain.");
        }
        if (column.getCategory() == BoardColumnCategory.TODO
                && countCategory(columns, BoardColumnCategory.TODO) <= 1) {
            throw new BadRequestException("At least one TODO column must remain.");
        }
        List<Task> tasks = taskRepository.findAllInColumnForDeletion(projectId, columnId);
        Long targetId = request == null ? null : request.targetColumnId();
        if (!tasks.isEmpty() && targetId == null) {
            throw new BadRequestException("Choose a target column for the tasks before deleting.");
        }
        if (targetId != null) {
            if (targetId.equals(columnId)) throw new BadRequestException("Choose a different target column.");
            ProjectBoardColumn target = columns.get(indexOf(columns, targetId));
            Long currentMax = taskRepository.findMaxBoardPosition(projectId, targetId);
            long highest = currentMax == null ? 0L : currentMax;
            for (Task task : tasks) {
                if (highest > Long.MAX_VALUE - 1_000_000L) {
                    throw new BadRequestException("The target column's order is full.");
                }
                highest += 1_000_000L;
                history.record(task, column, target, user);
                task.setStatus(target);
                task.setBoardPosition(highest);
            }
            taskRepository.saveAllAndFlush(tasks);
        }
        column.setDeletedAt(Instant.now());
        columnRepository.saveAndFlush(column);
        columns.remove(column);
        reorderPersisted(columns);
    }

    private Project lockedProject(User user, Long projectId) {
        Project project = projectAccess.requireActive(projectId, user, ProjectPermission.CONFIGURE_BOARD).project();
        // Serialize writes with task moves (which also lock the project row).
        projectRepository.lockById(projectId);
        return project;
    }

    private List<ProjectBoardColumn> active(Long projectId) {
        return new ArrayList<>(columnRepository.findByBoardProjectIdOrderByPositionAscIdAsc(projectId));
    }

    private static int indexOf(List<ProjectBoardColumn> columns, Long id) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getId().equals(id)) return i;
        }
        throw new BadRequestException("Column does not belong to this board.");
    }

    private static boolean isKanbanBacklog(Project project, List<ProjectBoardColumn> columns,
                                            ProjectBoardColumn column) {
        return project.getTemplate() == ProjectTemplate.KANBAN
                && !columns.isEmpty() && columns.get(0).getId().equals(column.getId());
    }

    private static long countCategory(List<ProjectBoardColumn> columns, BoardColumnCategory category) {
        return columns.stream().filter(c -> c.getCategory() == category).count();
    }

    private static String validateName(String raw, Long selfId, List<ProjectBoardColumn> columns) {
        if (raw == null || raw.isBlank() || raw.trim().length() > 100) {
            throw new BadRequestException("Column name must be 1–100 non-blank characters.");
        }
        String name = raw.trim();
        boolean conflict = columns.stream().anyMatch(c -> !c.getId().equals(selfId)
                && c.getName().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)));
        if (conflict) throw new BadRequestException("A column with this name already exists.");
        return name;
    }

    /** Two-phase write avoids transient uniqueness collisions when columns exchange positions. */
    private void reorderPersisted(List<ProjectBoardColumn> columns) {
        for (int i = 0; i < columns.size(); i++) columns.get(i).setPosition(1000 + i);
        columnRepository.saveAllAndFlush(columns);
        for (int i = 0; i < columns.size(); i++) columns.get(i).setPosition(i);
        columnRepository.saveAllAndFlush(columns);
    }
}
