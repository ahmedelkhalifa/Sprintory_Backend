package team.sprintory_backend.project.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.project.dto.AddProjectMembersRequest;
import team.sprintory_backend.project.dto.AddTeamToProjectRequest;
import team.sprintory_backend.project.dto.CreateProjectRequest;
import team.sprintory_backend.project.dto.ProjectMemberResponse;
import team.sprintory_backend.project.dto.ProjectResponse;
import team.sprintory_backend.project.services.ProjectService;
import team.sprintory_backend.security.userdetails.AppUserDetails;

/**
 * R02 project endpoints. Creating and listing live under the workspace a project belongs to;
 * everything else is addressed by project id. Access rules live in the service, not here.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @PostMapping("/workspaces/{workspaceId}/projects")
    public ResponseEntity<ProjectResponse> create(@AuthenticationPrincipal AppUserDetails principal,
                                                  @PathVariable Long workspaceId,
                                                  @Valid @RequestBody CreateProjectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectService.create(principal.getUser(), workspaceId, request));
    }

    /** {@code ?q=} searches the project name; {@code ?page=&size=} pages (max 100). */
    @GetMapping("/workspaces/{workspaceId}/projects")
    public PageResponse<ProjectResponse> list(@AuthenticationPrincipal AppUserDetails principal,
                                              @PathVariable Long workspaceId,
                                              @RequestParam(required = false) String q,
                                              Pageable pageable) {
        return projectService.list(principal.getUser(), workspaceId, q, pageable);
    }

    @GetMapping("/projects/{projectId}")
    public ProjectResponse get(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long projectId) {
        return projectService.get(principal.getUser(), projectId);
    }

    /** {@code ?q=} searches first name, last name and email; {@code ?page=&size=} pages (max 100). */
    @GetMapping("/projects/{projectId}/members")
    public PageResponse<ProjectMemberResponse> listMembers(@AuthenticationPrincipal AppUserDetails principal,
                                                           @PathVariable Long projectId,
                                                           @RequestParam(required = false) String q,
                                                           Pageable pageable) {
        return projectService.listMembers(principal.getUser(), projectId, q, pageable);
    }

    @PostMapping("/projects/{projectId}/members")
    public ResponseEntity<Void> addMembers(@AuthenticationPrincipal AppUserDetails principal,
                                           @PathVariable Long projectId,
                                           @Valid @RequestBody AddProjectMembersRequest request) {
        projectService.addMembers(principal.getUser(), projectId, request);
        return ResponseEntity.noContent().build();
    }

    /** Copies the team's current members into the project; it does not link the team. */
    @PostMapping("/projects/{projectId}/teams")
    public ResponseEntity<Void> addTeam(@AuthenticationPrincipal AppUserDetails principal,
                                        @PathVariable Long projectId,
                                        @Valid @RequestBody AddTeamToProjectRequest request) {
        projectService.addTeam(principal.getUser(), projectId, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/projects/{projectId}/members/{userId}")
    public ResponseEntity<Void> removeMember(@AuthenticationPrincipal AppUserDetails principal,
                                             @PathVariable Long projectId,
                                             @PathVariable Long userId) {
        projectService.removeMember(principal.getUser(), projectId, userId);
        return ResponseEntity.noContent().build();
    }
}