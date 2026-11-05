package team.sprintory_backend.task.controllers;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.task.dto.CreateSubtaskRequest;
import team.sprintory_backend.task.dto.TaskResponse;
import team.sprintory_backend.task.dto.UpdateSubtaskRequest;
import team.sprintory_backend.task.services.SubtaskService;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks/{parentId}/subtasks")
@RequiredArgsConstructor
public class SubtaskController {
    private final SubtaskService subtasks;

    @PostMapping
    public ResponseEntity<TaskResponse> create(@AuthenticationPrincipal AppUserDetails principal,
                                               @PathVariable Long projectId,
                                               @PathVariable Long parentId,
                                               @Valid @RequestBody CreateSubtaskRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(subtasks.create(principal.getUser(), projectId, parentId, request));
    }

    @GetMapping
    public List<TaskResponse> list(@AuthenticationPrincipal AppUserDetails principal,
                                   @PathVariable Long projectId, @PathVariable Long parentId) {
        return subtasks.list(principal.getUser(), projectId, parentId);
    }

    @PatchMapping("/{subtaskId}")
    public TaskResponse update(@AuthenticationPrincipal AppUserDetails principal,
                               @PathVariable Long projectId, @PathVariable Long parentId,
                               @PathVariable Long subtaskId,
                               @Valid @RequestBody UpdateSubtaskRequest request) {
        return subtasks.update(principal.getUser(), projectId, parentId, subtaskId, request);
    }
}
