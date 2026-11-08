package team.sprintory_backend.sprint.controllers;

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
import team.sprintory_backend.sprint.dto.*;
import team.sprintory_backend.sprint.services.SprintService;
import java.util.List;

@RestController @RequestMapping("/api/v1") @RequiredArgsConstructor
public class SprintController {
    private final SprintService sprints;

    @GetMapping("/projects/{projectId}/sprint-length")
    public java.util.Map<String, Integer> getSprintLength(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long projectId) {
        return java.util.Map.of("sprintLengthDays", sprints.projectSprintLength(principal.getUser(), projectId));
    }

    @PutMapping("/projects/{projectId}/sprint-length")
    public java.util.Map<String, Integer> sprintLength(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long projectId, @Valid @RequestBody SprintLengthRequest body) {
        return java.util.Map.of("sprintLengthDays", sprints.setProjectSprintLength(
                principal.getUser(), projectId, body));
    }

    @GetMapping("/projects/{projectId}/sprints")
    public List<SprintResponse> list(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long projectId) {
        return sprints.list(principal.getUser(), projectId);
    }

    @PostMapping("/projects/{projectId}/sprints")
    public ResponseEntity<SprintResponse> create(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long projectId, @Valid @RequestBody SprintRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sprints.create(principal.getUser(), projectId, body));
    }

    @GetMapping("/sprints/{sprintId}")
    public SprintResponse get(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long sprintId) {
        return sprints.get(principal.getUser(), sprintId);
    }

    @PatchMapping("/sprints/{sprintId}")
    public SprintResponse update(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long sprintId,
            @Valid @RequestBody SprintRequest body) {
        return sprints.update(principal.getUser(), sprintId, body);
    }

    @DeleteMapping("/sprints/{sprintId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long sprintId) {
        sprints.delete(principal.getUser(), sprintId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sprints/{sprintId}/start")
    public SprintResponse start(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long sprintId,
            @RequestBody(required = false) @Valid StartSprintRequest body) {
        return sprints.start(principal.getUser(), sprintId, body);
    }

    @PostMapping("/sprints/{sprintId}/complete")
    public CompleteSprintResponse complete(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long sprintId, @RequestBody(required = false) CompleteSprintRequest body) {
        return sprints.complete(principal.getUser(), sprintId, body);
    }

    @PostMapping("/projects/{projectId}/sprints/move")
    public ResponseEntity<Void> move(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long projectId, @Valid @RequestBody MoveSprintTaskRequest body) {
        sprints.move(principal.getUser(), projectId, body);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sprints/{sprintId}/capacity-suggestion")
    public CapacitySuggestionResponse capacity(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long sprintId) {
        return sprints.suggestion(principal.getUser(), sprintId);
    }

    @PutMapping("/sprints/{sprintId}/availability/{userId}")
    public CapacitySuggestionResponse availability(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long sprintId, @PathVariable Long userId, @Valid @RequestBody AvailabilityRequest body) {
        return sprints.setAvailability(principal.getUser(), sprintId, userId, body);
    }

    @GetMapping("/sprints/{sprintId}/commitment")
    public List<SprintCommitmentResponse> commitment(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long sprintId) {
        return sprints.committedItems(principal.getUser(), sprintId);
    }

    @GetMapping("/sprints/{sprintId}/scope-history")
    public PageResponse<SprintScopeChangeResponse> history(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long sprintId, Pageable pageable) {
        return sprints.scopeHistory(principal.getUser(), sprintId, Pageables.withoutSort(pageable));
    }
}
