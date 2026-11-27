package team.sprintory_backend.milestone.controllers;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import team.sprintory_backend.exceptionHandler.ErrorCode;
import team.sprintory_backend.exceptionHandler.ErrorResponse;
import team.sprintory_backend.exceptionHandler.ExceptionHandler;
import team.sprintory_backend.milestone.dto.MilestoneRequest;
import team.sprintory_backend.milestone.dto.MilestoneResponse;
import team.sprintory_backend.milestone.dto.MilestoneTasksRequest;
import team.sprintory_backend.milestone.dto.TaskMilestoneRequest;
import team.sprintory_backend.milestone.exceptions.MilestoneConflictException;
import team.sprintory_backend.milestone.services.MilestoneService;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.task.dto.TaskResponse;
import team.sprintory_backend.user.models.User;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Status codes and the milestone 409 mapping, without booting Spring. */
class MilestoneControllerTest {

    private MilestoneService service;
    private MilestoneController controller;
    private AppUserDetails principal;
    private User actor;

    @BeforeEach
    void setUp() {
        service = mock(MilestoneService.class);
        controller = new MilestoneController(service);
        actor = User.builder().id(1L).firstName("A").lastName("B").email("a@example.test").build();
        principal = new AppUserDetails(actor);
    }

    @Test
    void createAnswers201AndDeleteAnswers204() {
        MilestoneRequest request = new MilestoneRequest("Beta release", LocalDate.of(2026, 12, 15), null);
        MilestoneResponse milestone = mock(MilestoneResponse.class);
        when(service.create(actor, 10L, request)).thenReturn(milestone);

        var created = controller.create(principal, 10L, request);
        var deleted = controller.delete(principal, 4L);

        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertSame(milestone, created.getBody());
        assertEquals(HttpStatus.NO_CONTENT, deleted.getStatusCode());
        verify(service).delete(actor, 4L);
    }

    @Test
    void readsEditsAndRestoreAnswerTheServicesResult() {
        MilestoneRequest request = new MilestoneRequest("Beta release", LocalDate.of(2026, 12, 15), null);
        MilestoneResponse milestone = mock(MilestoneResponse.class);
        when(service.get(actor, 4L)).thenReturn(milestone);
        when(service.update(actor, 4L, request)).thenReturn(milestone);
        when(service.restore(actor, 4L)).thenReturn(milestone);

        assertSame(milestone, controller.get(principal, 4L));
        assertSame(milestone, controller.update(principal, 4L, request));
        assertSame(milestone, controller.restore(principal, 4L));
        controller.list(principal, 10L);
        verify(service).list(actor, 10L);
    }

    @Test
    void linkingAnswersTheServicesResult() {
        MilestoneTasksRequest link = new MilestoneTasksRequest(List.of(1L, 2L));
        TaskMilestoneRequest set = new TaskMilestoneRequest(null);
        MilestoneResponse milestone = mock(MilestoneResponse.class);
        TaskResponse task = mock(TaskResponse.class);
        when(service.linkTasks(actor, 4L, link)).thenReturn(milestone);
        when(service.setTaskMilestone(actor, 7L, set)).thenReturn(task);

        assertSame(milestone, controller.linkTasks(principal, 4L, link));
        assertSame(task, controller.setTaskMilestone(principal, 7L, set));
    }

    @Test
    void everyMilestoneConflictIs409WithItsOwnCode() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/projects/10/milestones");
        ExceptionHandler handler = new ExceptionHandler();

        assertConflict(handler.handleMilestoneConflict(MilestoneConflictException.nameTaken(), request),
                ErrorCode.MILESTONE_NAME_TAKEN, "A milestone with this name already exists in this project.");
        assertConflict(handler.handleMilestoneConflict(MilestoneConflictException.limitReached(50), request),
                ErrorCode.MILESTONE_LIMIT_REACHED, "A project can have at most 50 milestones.");
        assertConflict(handler.handleMilestoneConflict(MilestoneConflictException.readOnlyProject(), request),
                ErrorCode.CONFLICT, "Milestones are read-only when the project is completed or archived.");
    }

    private static void assertConflict(ResponseEntity<ErrorResponse> response, ErrorCode code, String message) {
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(code, response.getBody().getCode());
        assertEquals(message, response.getBody().getMessage());
    }
}
