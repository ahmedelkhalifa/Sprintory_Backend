package team.sprintory_backend.milestone.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import team.sprintory_backend.milestone.dto.MilestoneListResponse;
import team.sprintory_backend.milestone.dto.MilestoneRequest;
import team.sprintory_backend.milestone.dto.MilestoneResponse;
import team.sprintory_backend.milestone.dto.MilestoneTasksRequest;
import team.sprintory_backend.milestone.dto.TaskMilestoneRequest;
import team.sprintory_backend.milestone.services.MilestoneService;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.task.dto.TaskResponse;

/** Milestones (R12), contract docs/milestones-docs.md. Rules live in {@link MilestoneService}. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MilestoneController {

    private final MilestoneService milestones;

    /** §3.1. */
    @GetMapping("/projects/{projectId}/milestones")
    public MilestoneListResponse list(@AuthenticationPrincipal AppUserDetails principal,
                                      @PathVariable Long projectId) {
        return milestones.list(principal.getUser(), projectId);
    }

    /** §3.2. */
    @PostMapping("/projects/{projectId}/milestones")
    public ResponseEntity<MilestoneResponse> create(@AuthenticationPrincipal AppUserDetails principal,
                                                    @PathVariable Long projectId,
                                                    @Valid @RequestBody MilestoneRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(milestones.create(principal.getUser(), projectId, request));
    }

    /** §3.3. */
    @GetMapping("/milestones/{milestoneId}")
    public MilestoneResponse get(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long milestoneId) {
        return milestones.get(principal.getUser(), milestoneId);
    }

    /** §3.4: replaces name, date and description. */
    @PutMapping("/milestones/{milestoneId}")
    public MilestoneResponse update(@AuthenticationPrincipal AppUserDetails principal,
                                    @PathVariable Long milestoneId,
                                    @Valid @RequestBody MilestoneRequest request) {
        return milestones.update(principal.getUser(), milestoneId, request);
    }

    /** §3.5. */
    @DeleteMapping("/milestones/{milestoneId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppUserDetails principal,
                                       @PathVariable Long milestoneId) {
        milestones.delete(principal.getUser(), milestoneId);
        return ResponseEntity.noContent().build();
    }

    /** §3.6. */
    @PostMapping("/milestones/{milestoneId}/restore")
    public MilestoneResponse restore(@AuthenticationPrincipal AppUserDetails principal,
                                     @PathVariable Long milestoneId) {
        return milestones.restore(principal.getUser(), milestoneId);
    }

    /** §3.7: all or nothing. */
    @PostMapping("/milestones/{milestoneId}/tasks")
    public MilestoneResponse linkTasks(@AuthenticationPrincipal AppUserDetails principal,
                                       @PathVariable Long milestoneId,
                                       @Valid @RequestBody MilestoneTasksRequest request) {
        return milestones.linkTasks(principal.getUser(), milestoneId, request);
    }

    /** §3.8: {@code milestoneId: null} unlinks. */
    @PutMapping("/tasks/{taskId}/milestone")
    public TaskResponse setTaskMilestone(@AuthenticationPrincipal AppUserDetails principal,
                                         @PathVariable Long taskId,
                                         @RequestBody TaskMilestoneRequest request) {
        return milestones.setTaskMilestone(principal.getUser(), taskId, request);
    }
}
