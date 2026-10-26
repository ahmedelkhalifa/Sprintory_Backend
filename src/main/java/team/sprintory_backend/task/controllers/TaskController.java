package team.sprintory_backend.task.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import team.sprintory_backend.common.Pageables;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.task.api.TaskListOrder;
import team.sprintory_backend.task.api.TaskSearchFilters;
import team.sprintory_backend.task.dto.CreateTaskRequest;
import team.sprintory_backend.task.dto.MoveTaskRequest;
import team.sprintory_backend.task.dto.ReorderBacklogRequest;
import team.sprintory_backend.task.dto.TaskResponse;
import team.sprintory_backend.task.dto.UpdateTaskRequest;
import team.sprintory_backend.task.services.TaskApiService;

/** PMP-105: REST shape and validation; actual persistence belongs to PMP-104. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks")
@RequiredArgsConstructor
public class TaskController {
    private final TaskApiService taskApiService;

    @PostMapping
    public ResponseEntity<TaskResponse> create(@AuthenticationPrincipal AppUserDetails principal,
                                               @PathVariable Long projectId,
                                               @Valid @RequestBody CreateTaskRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(taskApiService.create(principal.getUser(), projectId, request));
    }

    @GetMapping
    public PageResponse<TaskResponse> list(@AuthenticationPrincipal AppUserDetails principal,
                                           @PathVariable Long projectId,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(required = false) String type,
                                           @RequestParam(required = false) String priority,
                                           @RequestParam(required = false) Long statusId,
                                           @RequestParam(required = false) Long assigneeId,
                                           @RequestParam(required = false) String label,
                                           @RequestParam(required = false) String order,
                                           Pageable pageable) {
        TaskSearchFilters filters = TaskSearchFilters.fromQuery(q, type, priority,
                statusId, assigneeId, label);
        return taskApiService.list(principal.getUser(), projectId, filters, TaskListOrder.fromQuery(order),
                Pageables.withoutSort(pageable));
    }

    @GetMapping("/{taskId}")
    public TaskResponse get(@AuthenticationPrincipal AppUserDetails principal,
                            @PathVariable Long projectId, @PathVariable Long taskId) {
        return taskApiService.get(principal.getUser(), projectId, taskId);
    }

    @PatchMapping("/{taskId}")
    public TaskResponse update(@AuthenticationPrincipal AppUserDetails principal,
                               @PathVariable Long projectId, @PathVariable Long taskId,
                               @Valid @RequestBody UpdateTaskRequest request) {
        return taskApiService.update(principal.getUser(), projectId, taskId, request);
    }

    /** Drag on the board: column and place in it ({@code afterTaskId} null = first). */
    @PutMapping("/{taskId}/position")
    public TaskResponse moveOnBoard(@AuthenticationPrincipal AppUserDetails principal,
                                    @PathVariable Long projectId, @PathVariable Long taskId,
                                    @Valid @RequestBody MoveTaskRequest request) {
        return taskApiService.moveOnBoard(principal.getUser(), projectId, taskId, request);
    }

    /** Drag in the Scrum backlog ({@code afterTaskId} null = first). */
    @PutMapping("/{taskId}/backlog-rank")
    public TaskResponse moveInBacklog(@AuthenticationPrincipal AppUserDetails principal,
                                      @PathVariable Long projectId, @PathVariable Long taskId,
                                      @Valid @RequestBody ReorderBacklogRequest request) {
        return taskApiService.moveInBacklog(principal.getUser(), projectId, taskId, request);
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppUserDetails principal,
                                       @PathVariable Long projectId, @PathVariable Long taskId) {
        taskApiService.delete(principal.getUser(), projectId, taskId);
        return ResponseEntity.noContent().build();
    }
}
