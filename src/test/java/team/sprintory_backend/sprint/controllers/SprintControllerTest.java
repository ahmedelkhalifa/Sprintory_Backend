package team.sprintory_backend.sprint.controllers;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import team.sprintory_backend.exceptionHandler.ErrorCode;
import team.sprintory_backend.exceptionHandler.ExceptionHandler;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.sprint.dto.SprintRequest;
import team.sprintory_backend.sprint.exceptions.SprintAlreadyActiveException;
import team.sprintory_backend.sprint.services.SprintService;
import team.sprintory_backend.user.models.User;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controller status contract and exception mapping without booting Spring. */
class SprintControllerTest {
    private SprintService service;
    private SprintController controller;
    private AppUserDetails principal;
    private User actor;

    @BeforeEach void setUp() {
        service = mock(SprintService.class);
        controller = new SprintController(service);
        actor = User.builder().id(1L).firstName("A").lastName("B").email("a@example.test").build();
        principal = new AppUserDetails(actor);
    }

    @Test void createReturns201AndDeleteReturns204() {
        SprintRequest request = new SprintRequest(null, "Goal", null, null, null);
        assertEquals(HttpStatus.CREATED, controller.create(principal, 4L, request).getStatusCode());
        verify(service).create(actor, 4L, request);
        assertEquals(HttpStatus.NO_CONTENT, controller.delete(principal, 6L).getStatusCode());
        verify(service).delete(actor, 6L);
    }

    @Test void activeSprintErrorMapsTo409WithStableCode() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/sprints/6/start");
        var response = new ExceptionHandler().handleSprintAlreadyActive(request);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(ErrorCode.SPRINT_ALREADY_ACTIVE, response.getBody().getCode());
    }

    @Test void archivedWorkspaceMapsTo409() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/sprints/6");
        var response = new ExceptionHandler().handleWorkspaceArchived(request);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(ErrorCode.WORKSPACE_ARCHIVED, response.getBody().getCode());
    }

    @Test void sprintLengthUsesTypedResponse() {
        when(service.projectSprintLength(actor, 4L)).thenReturn(14);
        assertEquals(14, controller.getSprintLength(principal, 4L).sprintLengthDays());
    }
}
