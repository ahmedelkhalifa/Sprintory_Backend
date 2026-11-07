package team.sprintory_backend.project.charter.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.project.charter.dto.*;
import team.sprintory_backend.project.charter.services.ProjectCharterService;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import java.util.List;

/** REST contract for PMP-116. All paths are within the authenticated /api/v1 router. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/charter")
@RequiredArgsConstructor
public class ProjectCharterController {
    private final ProjectCharterService charters;

    @GetMapping
    public CharterResponse get(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long projectId) {
        return charters.get(principal.getUser(), projectId);
    }

    @GetMapping("/history")
    public PageResponse<CharterHistoryResponse> history(@AuthenticationPrincipal AppUserDetails principal,
                                                         @PathVariable Long projectId, Pageable pageable) {
        return charters.history(principal.getUser(), projectId, pageable);
    }

    @PostMapping("/goals")
    public ResponseEntity<CharterGoalResponse> createGoal(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long projectId, @Valid @RequestBody CharterGoalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(charters.addGoal(principal.getUser(), projectId, request));
    }

    @PutMapping("/goals/{goalId}")
    public CharterGoalResponse updateGoal(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long projectId, @PathVariable Long goalId,
            @Valid @RequestBody CharterGoalRequest request) {
        return charters.updateGoal(principal.getUser(), projectId, goalId, request);
    }

    @DeleteMapping("/goals/{goalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGoal(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long projectId, @PathVariable Long goalId) {
        charters.removeGoal(principal.getUser(), projectId, goalId);
    }

    @PutMapping("/goals/order")
    public List<CharterGoalResponse> reorder(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long projectId, @Valid @RequestBody CharterGoalOrderRequest request) {
        return charters.reorderGoals(principal.getUser(), projectId, request);
    }

    @PutMapping("/scope")
    public CharterResponse scope(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long projectId,
                                 @RequestBody CharterScopeRequest request) {
        return charters.replaceScope(principal.getUser(), projectId, request);
    }

    @PostMapping("/stakeholders")
    public ResponseEntity<CharterStakeholderResponse> createStakeholder(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long projectId, @Valid @RequestBody CharterStakeholderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(charters.addStakeholder(principal.getUser(), projectId, request));
    }

    @PutMapping("/stakeholders/{stakeholderId}")
    public CharterStakeholderResponse updateStakeholder(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long projectId, @PathVariable Long stakeholderId,
            @Valid @RequestBody CharterStakeholderRequest request) {
        return charters.updateStakeholder(principal.getUser(), projectId, stakeholderId, request);
    }

    @DeleteMapping("/stakeholders/{stakeholderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStakeholder(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long projectId, @PathVariable Long stakeholderId) {
        charters.removeStakeholder(principal.getUser(), projectId, stakeholderId);
    }
}
