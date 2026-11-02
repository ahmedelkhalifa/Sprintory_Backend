package team.sprintory_backend.project.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import team.sprintory_backend.project.dto.*;
import team.sprintory_backend.project.services.ProjectBoardService;
import team.sprintory_backend.security.userdetails.AppUserDetails;

/** PMP-125: board column mutations. The service independently enforces CONFIGURE_BOARD. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/board/columns")
@RequiredArgsConstructor
public class ProjectBoardColumnController {
    private final ProjectBoardService service;

    @PostMapping
    public ResponseEntity<ProjectBoardResponse> add(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long projectId, @Valid @RequestBody CreateBoardColumnRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.add(principal.getUser(), projectId, request));
    }

    @PatchMapping("/{columnId}")
    public ProjectBoardResponse update(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long projectId, @PathVariable Long columnId,
            @Valid @RequestBody UpdateBoardColumnRequest request) {
        return service.update(principal.getUser(), projectId, columnId, request);
    }

    @PutMapping("/order")
    public ProjectBoardResponse reorder(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long projectId, @Valid @RequestBody ReorderBoardColumnsRequest request) {
        return service.reorder(principal.getUser(), projectId, request);
    }

    @DeleteMapping("/{columnId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long projectId, @PathVariable Long columnId,
            @Valid @RequestBody(required = false) DeleteBoardColumnRequest request) {
        service.delete(principal.getUser(), projectId, columnId, request);
        return ResponseEntity.noContent().build();
    }
}
