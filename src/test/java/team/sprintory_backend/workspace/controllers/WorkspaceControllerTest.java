package team.sprintory_backend.workspace.controllers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.dto.CreateWorkspaceRequest;
import team.sprintory_backend.workspace.dto.InvitationLookupResponse;
import team.sprintory_backend.workspace.dto.InvitationTokenRequest;
import team.sprintory_backend.workspace.dto.InvitationsSentResponse;
import team.sprintory_backend.workspace.dto.InviteMembersRequest;
import team.sprintory_backend.workspace.dto.UpdateWorkspaceRequest;
import team.sprintory_backend.workspace.dto.WorkspaceMemberResponse;
import team.sprintory_backend.workspace.dto.WorkspaceResponse;
import team.sprintory_backend.workspace.services.InvitationService;
import team.sprintory_backend.workspace.services.WorkspaceService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pure controller tests for WorkspaceController and InvitationController: the services are
 * mocked, so no database or Spring context. They check status codes and that the logged-in
 * user reaches the service.
 */
class WorkspaceControllerTest {

    private static final WorkspaceResponse WORKSPACE =
            new WorkspaceResponse(3L, "Acme", "acme", Role.WORKSPACE_ADMIN, null);

    private WorkspaceService workspaceService;
    private InvitationService invitationService;
    private WorkspaceController workspaceController;
    private InvitationController invitationController;
    private User user;
    private AppUserDetails principal;

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        invitationService = mock(InvitationService.class);
        workspaceController = new WorkspaceController(workspaceService);
        invitationController = new InvitationController(invitationService);
        user = User.builder().id(7L).firstName("Ada").lastName("Lovelace").email("ada@example.com").build();
        principal = new AppUserDetails(user);
    }

    // --- workspaces ---

    @Test
    void createAnswersCreatedWithTheWorkspace() {
        CreateWorkspaceRequest request = new CreateWorkspaceRequest("Acme");
        when(workspaceService.create(user, request)).thenReturn(WORKSPACE);

        var response = workspaceController.create(principal, request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(WORKSPACE, response.getBody());
    }

    @Test
    void listMineReturnsTheCallersWorkspaces() {
        when(workspaceService.listMine(user)).thenReturn(List.of(WORKSPACE));

        assertEquals(List.of(WORKSPACE), workspaceController.listMine(principal));
    }

    @Test
    void getReturnsTheWorkspace() {
        when(workspaceService.get(user, 3L)).thenReturn(WORKSPACE);

        assertEquals(WORKSPACE, workspaceController.get(principal, 3L));
    }

    @Test
    void listMembersPassesSearchAndPageThrough() {
        PageRequest pageable = PageRequest.of(1, 5);
        PageResponse<WorkspaceMemberResponse> page = new PageResponse<>(List.of(), 1, 5, 0, 0);
        when(workspaceService.listMembers(user, 3L, "ada", pageable)).thenReturn(page);

        assertSame(page, workspaceController.listMembers(principal, 3L, "ada", pageable));
    }

    @Test
    void renameReturnsTheRenamedWorkspace() {
        UpdateWorkspaceRequest request = new UpdateWorkspaceRequest("Acme Labs");
        when(workspaceService.rename(user, 3L, request)).thenReturn(WORKSPACE);

        assertEquals(WORKSPACE, workspaceController.rename(principal, 3L, request));
    }

    @Test
    void archiveAndRestoreReturnTheWorkspace() {
        when(workspaceService.archive(user, 3L)).thenReturn(WORKSPACE);
        when(workspaceService.restore(user, 3L)).thenReturn(WORKSPACE);

        assertEquals(WORKSPACE, workspaceController.archive(principal, 3L));
        assertEquals(WORKSPACE, workspaceController.restore(principal, 3L));
    }

    @Test
    void deleteAnswersNoContent() {
        var response = workspaceController.delete(principal, 3L);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());
        verify(workspaceService).delete(user, 3L);
    }

    // --- invitations ---

    @Test
    void inviteAnswersCreatedWithWhoWasInvitedAndSkipped() {
        InviteMembersRequest request = new InviteMembersRequest(List.of("grace@example.com"));
        InvitationsSentResponse sent = new InvitationsSentResponse(List.of("grace@example.com"), List.of());
        when(invitationService.invite(user, 3L, request)).thenReturn(sent);

        var response = invitationController.invite(principal, 3L, request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(sent, response.getBody());
    }

    @Test
    void lookupNeedsNoUser() {
        InvitationLookupResponse lookup = new InvitationLookupResponse("Acme", "grace@example.com");
        when(invitationService.lookup("tok")).thenReturn(lookup);

        assertEquals(lookup, invitationController.lookup(new InvitationTokenRequest("tok")));
    }

    @Test
    void acceptPassesTheLoggedInUser() {
        when(invitationService.accept(user, "tok")).thenReturn(WORKSPACE);

        assertEquals(WORKSPACE, invitationController.accept(principal, new InvitationTokenRequest("tok")));
    }
}