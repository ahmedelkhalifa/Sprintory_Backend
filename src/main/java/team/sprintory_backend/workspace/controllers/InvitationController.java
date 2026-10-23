package team.sprintory_backend.workspace.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.workspace.dto.InvitationLookupResponse;
import team.sprintory_backend.workspace.dto.InvitationTokenRequest;
import team.sprintory_backend.workspace.dto.InvitationsSentResponse;
import team.sprintory_backend.workspace.dto.InviteMembersRequest;
import team.sprintory_backend.workspace.dto.WorkspaceResponse;
import team.sprintory_backend.workspace.services.InvitationService;

/**
 * R02 invitation endpoints. Tokens travel in the request body, never the URL, so they stay out
 * of request logs. {@code /invitations/lookup} is the only one open without login.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitationService;

    @PostMapping("/workspaces/{workspaceId}/invitations")
    public ResponseEntity<InvitationsSentResponse> invite(@AuthenticationPrincipal AppUserDetails principal,
                                                          @PathVariable Long workspaceId,
                                                          @Valid @RequestBody InviteMembersRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(invitationService.invite(principal.getUser(), workspaceId, request));
    }

    @PostMapping("/invitations/lookup")
    public InvitationLookupResponse lookup(@Valid @RequestBody InvitationTokenRequest request) {
        return invitationService.lookup(request.token());
    }

    @PostMapping("/invitations/accept")
    public WorkspaceResponse accept(@AuthenticationPrincipal AppUserDetails principal,
                                    @Valid @RequestBody InvitationTokenRequest request) {
        return invitationService.accept(principal.getUser(), request.token());
    }
}