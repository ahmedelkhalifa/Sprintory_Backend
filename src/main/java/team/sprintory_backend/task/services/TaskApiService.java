package team.sprintory_backend.task.services;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.task.api.TaskSearchFilters;
import team.sprintory_backend.task.dto.CreateTaskRequest;
import team.sprintory_backend.task.dto.TaskResponse;
import team.sprintory_backend.task.dto.UpdateTaskRequest;
import team.sprintory_backend.user.models.User;

/**
 * PMP-105 REST-facing service contract. Ahmed fills the TODOs here against the PMP-104 Task
 * persistence/services. No fake Task objects or in-memory persistence are introduced.
 *
 * IMPORTANT: leave ProjectAccess checks in place (and ensure task IDs belong to projectId).
 */
@Service
@RequiredArgsConstructor
public class TaskApiService {

    private final ProjectAccess projectAccess;

    public TaskResponse create(User user, Long projectId, CreateTaskRequest request) {
        projectAccess.requireActive(projectId, user, ProjectPermission.EDIT_TASKS);
        // TODO(PMP-104): generate immutable unique project key and sequence; defaults TASK/MEDIUM;
        // verify assignee membership; assign Scrum backlog/no sprint or Kanban Backlog column.
        throw pending();
    }

    public PageResponse<TaskResponse> list(User user, Long projectId, TaskSearchFilters filters,
                                           Pageable pageable) {
        projectAccess.require(projectId, user, ProjectPermission.VIEW_WORK);
        // TODO(PMP-104): query non-deleted tasks in this project, AND all filters, OR q over
        // title/key (literal case-insensitive substring), stable ordering and pagination.
        throw pending();
    }

    public TaskResponse get(User user, Long projectId, Long taskId) {
        projectAccess.require(projectId, user, ProjectPermission.VIEW_WORK);
        // TODO(PMP-104): return 404 for nonexisting/deleted task or task from another project.
        throw pending();
    }

    public TaskResponse update(User user, Long projectId, Long taskId, UpdateTaskRequest request) {
        projectAccess.requireActive(projectId, user, ProjectPermission.EDIT_TASKS);
        // TODO(PMP-104): update mutable fields, honor clear flags, validate board column
        // and assignee project membership, reject moved/deleted/wrong-project tasks.
        throw pending();
    }

    public void delete(User user, Long projectId, Long taskId) {
        projectAccess.requireActive(projectId, user, ProjectPermission.EDIT_TASKS);
        // TODO(PMP-104): soft delete only; never reuse task key; 404 for wrong-project tasks.
        throw pending();
    }

    private static ResponseStatusException pending() {
        return new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                "Task operations are pending PMP-104 integration.");
    }
}
