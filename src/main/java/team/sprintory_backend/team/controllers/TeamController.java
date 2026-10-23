package team.sprintory_backend.team.controllers;

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
import team.sprintory_backend.team.dto.AddTeamMembersRequest;
import team.sprintory_backend.team.dto.CreateTeamRequest;
import team.sprintory_backend.team.dto.TeamMemberResponse;
import team.sprintory_backend.team.dto.TeamResponse;
import team.sprintory_backend.team.dto.UpdateTeamRequest;
import team.sprintory_backend.team.services.TeamService;

/**
 * R02 team endpoints. Creating and listing live under the workspace a team belongs to;
 * everything else is addressed by team id. Access rules live in the service, not here.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;

    @PostMapping("/workspaces/{workspaceId}/teams")
    public ResponseEntity<TeamResponse> create(@AuthenticationPrincipal AppUserDetails principal,
                                               @PathVariable Long workspaceId,
                                               @Valid @RequestBody CreateTeamRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(teamService.create(principal.getUser(), workspaceId, request));
    }

    /** {@code ?q=} searches the team name; {@code ?page=&size=} pages (max 100). */
    @GetMapping("/workspaces/{workspaceId}/teams")
    public PageResponse<TeamResponse> list(@AuthenticationPrincipal AppUserDetails principal,
                                           @PathVariable Long workspaceId,
                                           @RequestParam(required = false) String q,
                                           Pageable pageable) {
        return teamService.list(principal.getUser(), workspaceId, q, pageable);
    }

    @GetMapping("/teams/{teamId}")
    public TeamResponse get(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long teamId) {
        return teamService.get(principal.getUser(), teamId);
    }

    @PatchMapping("/teams/{teamId}")
    public TeamResponse rename(@AuthenticationPrincipal AppUserDetails principal,
                               @PathVariable Long teamId,
                               @Valid @RequestBody UpdateTeamRequest request) {
        return teamService.rename(principal.getUser(), teamId, request);
    }

    @DeleteMapping("/teams/{teamId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppUserDetails principal,
                                       @PathVariable Long teamId) {
        teamService.delete(principal.getUser(), teamId);
        return ResponseEntity.noContent().build();
    }

    /** {@code ?q=} searches first name, last name and email; {@code ?page=&size=} pages (max 100). */
    @GetMapping("/teams/{teamId}/members")
    public PageResponse<TeamMemberResponse> listMembers(@AuthenticationPrincipal AppUserDetails principal,
                                                        @PathVariable Long teamId,
                                                        @RequestParam(required = false) String q,
                                                        Pageable pageable) {
        return teamService.listMembers(principal.getUser(), teamId, q, pageable);
    }

    @PostMapping("/teams/{teamId}/members")
    public ResponseEntity<Void> addMembers(@AuthenticationPrincipal AppUserDetails principal,
                                           @PathVariable Long teamId,
                                           @Valid @RequestBody AddTeamMembersRequest request) {
        teamService.addMembers(principal.getUser(), teamId, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/teams/{teamId}/members/{userId}")
    public ResponseEntity<Void> removeMember(@AuthenticationPrincipal AppUserDetails principal,
                                             @PathVariable Long teamId,
                                             @PathVariable Long userId) {
        teamService.removeMember(principal.getUser(), teamId, userId);
        return ResponseEntity.noContent().build();
    }
}