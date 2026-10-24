package team.sprintory_backend.project.controllers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.project.dto.AddProjectMembersRequest;
import team.sprintory_backend.project.dto.AddTeamToProjectRequest;
import team.sprintory_backend.project.dto.CreateProjectRequest;
import team.sprintory_backend.project.dto.ProjectMemberResponse;
import team.sprintory_backend.project.dto.ProjectResponse;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.services.ProjectService;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.user.models.User;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Pure controller tests: the service is mocked, so no database or Spring context. */
class ProjectControllerTest {

    private static final ProjectResponse PROJECT = new ProjectResponse(21L, 3L, "Mobile App");

    private ProjectService service;
    private ProjectController controller;
    private User user;
    private AppUserDetails principal;

    @BeforeEach
    void setUp() {
        service = mock(ProjectService.class);
        controller = new ProjectController(service);
        user = User.builder().id(7L).firstName("Ada").lastName("Lovelace").email("ada@example.com").build();
        principal = new AppUserDetails(user);
    }

    @Test
    void createAnswersCreatedWithTheProject() {
        CreateProjectRequest request = new CreateProjectRequest("Mobile App", ProjectTemplate.SCRUM);
        when(service.create(user, 3L, request)).thenReturn(PROJECT);

        var response = controller.create(principal, 3L, request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(PROJECT, response.getBody());
    }

    @Test
    void listPassesSearchAndPageThrough() {
        PageRequest pageable = PageRequest.of(0, 20);
        PageResponse<ProjectResponse> page = new PageResponse<>(List.of(PROJECT), 0, 20, 1, 1);
        when(service.list(user, 3L, "mob", pageable)).thenReturn(page);

        assertSame(page, controller.list(principal, 3L, "mob", pageable));
    }

    @Test
    void getReturnsTheProject() {
        when(service.get(user, 21L)).thenReturn(PROJECT);

        assertEquals(PROJECT, controller.get(principal, 21L));
    }

    @Test
    void listMembersPassesSearchAndPageThrough() {
        PageRequest pageable = PageRequest.of(0, 20);
        PageResponse<ProjectMemberResponse> page = new PageResponse<>(List.of(), 0, 20, 0, 0);
        when(service.listMembers(user, 21L, "ada", pageable)).thenReturn(page);

        assertSame(page, controller.listMembers(principal, 21L, "ada", pageable));
    }

    @Test
    void membershipChangesAnswerNoContent() {
        AddProjectMembersRequest people = new AddProjectMembersRequest(List.of(8L));
        AddTeamToProjectRequest team = new AddTeamToProjectRequest(4L);

        assertEquals(HttpStatus.NO_CONTENT, controller.addMembers(principal, 21L, people).getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT, controller.addTeam(principal, 21L, team).getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT, controller.removeMember(principal, 21L, 8L).getStatusCode());
        verify(service).addMembers(user, 21L, people);
        verify(service).addTeam(user, 21L, team);
        verify(service).removeMember(user, 21L, 8L);
    }
}