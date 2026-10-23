package team.sprintory_backend.team.controllers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.team.dto.AddTeamMembersRequest;
import team.sprintory_backend.team.dto.CreateTeamRequest;
import team.sprintory_backend.team.dto.TeamMemberResponse;
import team.sprintory_backend.team.dto.TeamResponse;
import team.sprintory_backend.team.dto.UpdateTeamRequest;
import team.sprintory_backend.team.services.TeamService;
import team.sprintory_backend.user.models.User;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Pure controller tests: the service is mocked, so no database or Spring context. */
class TeamControllerTest {

    private static final TeamResponse TEAM = new TeamResponse(4L, 3L, "Backend");

    private TeamService service;
    private TeamController controller;
    private User user;
    private AppUserDetails principal;

    @BeforeEach
    void setUp() {
        service = mock(TeamService.class);
        controller = new TeamController(service);
        user = User.builder().id(7L).firstName("Ada").lastName("Lovelace").email("ada@example.com").build();
        principal = new AppUserDetails(user);
    }

    @Test
    void createAnswersCreatedWithTheTeam() {
        CreateTeamRequest request = new CreateTeamRequest("Backend");
        when(service.create(user, 3L, request)).thenReturn(TEAM);

        var response = controller.create(principal, 3L, request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(TEAM, response.getBody());
    }

    @Test
    void listPassesSearchAndPageThrough() {
        PageRequest pageable = PageRequest.of(0, 20);
        PageResponse<TeamResponse> page = new PageResponse<>(List.of(TEAM), 0, 20, 1, 1);
        when(service.list(user, 3L, "back", pageable)).thenReturn(page);

        assertSame(page, controller.list(principal, 3L, "back", pageable));
    }

    @Test
    void getAndRenameReturnTheTeam() {
        UpdateTeamRequest request = new UpdateTeamRequest("Platform");
        when(service.get(user, 4L)).thenReturn(TEAM);
        when(service.rename(user, 4L, request)).thenReturn(TEAM);

        assertEquals(TEAM, controller.get(principal, 4L));
        assertEquals(TEAM, controller.rename(principal, 4L, request));
    }

    @Test
    void deleteAnswersNoContent() {
        var response = controller.delete(principal, 4L);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service).delete(user, 4L);
    }

    @Test
    void listMembersPassesSearchAndPageThrough() {
        PageRequest pageable = PageRequest.of(0, 20);
        PageResponse<TeamMemberResponse> page = new PageResponse<>(List.of(), 0, 20, 0, 0);
        when(service.listMembers(user, 4L, "ada", pageable)).thenReturn(page);

        assertSame(page, controller.listMembers(principal, 4L, "ada", pageable));
    }

    @Test
    void addAndRemoveMembersAnswerNoContent() {
        AddTeamMembersRequest request = new AddTeamMembersRequest(List.of(8L));

        assertEquals(HttpStatus.NO_CONTENT, controller.addMembers(principal, 4L, request).getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT, controller.removeMember(principal, 4L, 8L).getStatusCode());
        verify(service).addMembers(user, 4L, request);
        verify(service).removeMember(user, 4L, 8L);
    }
}