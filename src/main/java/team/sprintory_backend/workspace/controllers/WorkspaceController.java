package team.sprintory_backend.workspace.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.workspace.dto.ChangeWorkspaceRoleRequest;
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

    /** {@code ?q=} searches first name, last name and email; {@code ?page=&size=} pages (max 100). */
    @GetMapping("/{workspaceId}/members")
    public PageResponse<WorkspaceMemberResponse> listMembers(@AuthenticationPrincipal AppUserDetails principal,
                                                             @PathVariable Long workspaceId,
                                                             @RequestParam(required = false) String q,
                                                             Pageable pageable) {
        return workspaceService.listMembers(principal.getUser(), workspaceId, q, pageable);
    }

    /** R03: workspace admins may change a member's workspace role; the last admin is protected. */
    @PatchMapping("/{workspaceId}/members/{userId}")
    public WorkspaceMemberResponse changeMemberRole(@AuthenticationPrincipal AppUserDetails principal,
                                                    @PathVariable Long workspaceId,
                                                    @PathVariable Long userId,
                                                    @Valid @RequestBody ChangeWorkspaceRoleRequest request) {
        return workspaceService.changeRole(principal.getUser(), workspaceId, userId, request.role());
    }

    /** An admin may remove a member; a member may leave by requesting their own user ID. */
    @DeleteMapping("/{workspaceId}/members/{userId}")
    public ResponseEntity<Void> removeMember(@AuthenticationPrincipal AppUserDetails principal,
                                             @PathVariable Long workspaceId,
                                             @PathVariable Long userId) {
        workspaceService.removeMember(principal.getUser(), workspaceId, userId);
        return ResponseEntity.noContent().build();
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