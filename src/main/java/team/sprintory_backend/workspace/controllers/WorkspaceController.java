package team.sprintory_backend.workspace.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.workspace.dto.CreateWorkspaceRequest;
import team.sprintory_backend.workspace.dto.UpdateWorkspaceRequest;
import team.sprintory_backend.workspace.dto.WorkspaceMemberResponse;
import team.sprintory_backend.workspace.dto.WorkspaceResponse;
import team.sprintory_backend.workspace.services.WorkspaceService;

import java.util.List;

/** R02 workspace endpoints. Access rules live in the service, not here. */
@RestController
@RequestMapping("/api/v1/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    @PostMapping
    public ResponseEntity<WorkspaceResponse> create(@AuthenticationPrincipal AppUserDetails principal,
                                                    @Valid @RequestBody CreateWorkspaceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(workspaceService.create(principal.getUser(), request));
    }

    @GetMapping
    public List<WorkspaceResponse> listMine(@AuthenticationPrincipal AppUserDetails principal) {
        return workspaceService.listMine(principal.getUser());
    }

    @GetMapping("/{workspaceId}")
    public WorkspaceResponse get(@AuthenticationPrincipal AppUserDetails principal,
                                 @PathVariable Long workspaceId) {
        return workspaceService.get(principal.getUser(), workspaceId);
    }

    @GetMapping("/{workspaceId}/members")
    public List<WorkspaceMemberResponse> listMembers(@AuthenticationPrincipal AppUserDetails principal,
                                                     @PathVariable Long workspaceId) {
        return workspaceService.listMembers(principal.getUser(), workspaceId);
    }

    @PatchMapping("/{workspaceId}")
    public WorkspaceResponse rename(@AuthenticationPrincipal AppUserDetails principal,
                                    @PathVariable Long workspaceId,
                                    @Valid @RequestBody UpdateWorkspaceRequest request) {
        return workspaceService.rename(principal.getUser(), workspaceId, request);
    }

    @PostMapping("/{workspaceId}/archive")
    public WorkspaceResponse archive(@AuthenticationPrincipal AppUserDetails principal,
                                     @PathVariable Long workspaceId) {
        return workspaceService.archive(principal.getUser(), workspaceId);
    }

    @PostMapping("/{workspaceId}/restore")
    public WorkspaceResponse restore(@AuthenticationPrincipal AppUserDetails principal,
                                     @PathVariable Long workspaceId) {
        return workspaceService.restore(principal.getUser(), workspaceId);
    }

    /** Permanent; the workspace must be archived first. */
    @DeleteMapping("/{workspaceId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppUserDetails principal,
                                       @PathVariable Long workspaceId) {
        workspaceService.delete(principal.getUser(), workspaceId);
        return ResponseEntity.noContent().build();
    }
}