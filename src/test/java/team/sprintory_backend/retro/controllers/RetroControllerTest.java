package team.sprintory_backend.retro.controllers;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import team.sprintory_backend.exceptionHandler.ErrorCode;
import team.sprintory_backend.exceptionHandler.ErrorResponse;
import team.sprintory_backend.exceptionHandler.ExceptionHandler;
import team.sprintory_backend.retro.dto.RetroActionRequest;
import team.sprintory_backend.retro.dto.RetroActionResponse;
import team.sprintory_backend.retro.dto.RetroItemRequest;
import team.sprintory_backend.retro.dto.RetroItemResponse;
import team.sprintory_backend.retro.dto.RetroResponse;
import team.sprintory_backend.retro.dto.RetroVoteResponse;
import team.sprintory_backend.retro.models.RetroColumn;
import team.sprintory_backend.retro.exceptions.RetroConflictException;
import team.sprintory_backend.retro.services.RetroService;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.user.models.User;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Status codes and the retro 409 mapping, without booting Spring. */
class RetroControllerTest {

    private RetroService service;
    private RetroController controller;
    private AppUserDetails principal;
    private User actor;

    @BeforeEach
    void setUp() {
        service = mock(RetroService.class);
        controller = new RetroController(service);
        actor = User.builder().id(1L).firstName("A").lastName("B").email("a@example.test").build();
        principal = new AppUserDetails(actor);
    }

    @Test
    void openAnswers201WhenCreatedAnd200WhenItExisted() {
        RetroResponse retro = mock(RetroResponse.class);
        when(service.open(actor, 42L))
                .thenReturn(new RetroService.Opened(retro, true))
                .thenReturn(new RetroService.Opened(retro, false));

        var created = controller.open(principal, 42L);
        var existing = controller.open(principal, 42L);

        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertSame(retro, created.getBody());
        assertEquals(HttpStatus.OK, existing.getStatusCode());
    }

    @Test
    void getDelegatesToTheService() {
        controller.get(principal, 7L);
        verify(service).get(actor, 7L);
    }

    @Test
    void closeAnswersTheClosedBoard() {
        RetroResponse closed = mock(RetroResponse.class);
        when(service.close(actor, 7L)).thenReturn(closed);

        assertSame(closed, controller.close(principal, 7L));
    }

    @Test
    void addingACardAnswers201AndDeletingOne204() {
        RetroItemRequest request = new RetroItemRequest(RetroColumn.IDEAS, "{}");
        RetroItemResponse card = mock(RetroItemResponse.class);
        when(service.addItem(actor, 7L, request)).thenReturn(card);

        var added = controller.addItem(principal, 7L, request);
        var deleted = controller.deleteItem(principal, 512L);

        assertEquals(HttpStatus.CREATED, added.getStatusCode());
        assertSame(card, added.getBody());
        assertEquals(HttpStatus.NO_CONTENT, deleted.getStatusCode());
        verify(service).deleteItem(actor, 512L);
    }

    @Test
    void votingAndUnvotingAnswerTheServicesResult() {
        RetroVoteResponse voted = new RetroVoteResponse(512L, 3, true, 1);
        RetroVoteResponse unvoted = new RetroVoteResponse(512L, 2, false, 2);
        when(service.vote(actor, 512L)).thenReturn(voted);
        when(service.unvote(actor, 512L)).thenReturn(unvoted);

        assertSame(voted, controller.vote(principal, 512L));
        assertSame(unvoted, controller.unvote(principal, 512L));
    }

    @Test
    void creatingAnActionAnswers201() {
        RetroActionRequest request = new RetroActionRequest("Time-box stand-ups", 31L, null, null);
        RetroActionResponse action = mock(RetroActionResponse.class);
        when(service.createAction(actor, 7L, request)).thenReturn(action);

        var created = controller.createAction(principal, 7L, request);

        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertSame(action, created.getBody());
    }

    @Test
    void everyRetroConflictIs409WithItsOwnCode() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/sprints/42/retro");
        ExceptionHandler handler = new ExceptionHandler();

        assertConflict(handler.handleRetroConflict(RetroConflictException.sprintNotEnded(), request),
                ErrorCode.RETRO_SPRINT_NOT_ENDED, "The sprint must be completed or cancelled first.");
        assertConflict(handler.handleRetroConflict(RetroConflictException.closed(), request),
                ErrorCode.RETRO_CLOSED, "This retrospective is closed.");
        assertConflict(handler.handleRetroConflict(RetroConflictException.alreadyVoted(), request),
                ErrorCode.ALREADY_VOTED, "You already voted on this card.");
        assertConflict(handler.handleRetroConflict(RetroConflictException.noVotesLeft(), request),
                ErrorCode.NO_VOTES_LEFT, "No votes left.");
        assertConflict(handler.handleRetroConflict(RetroConflictException.readOnlyProject(), request),
                ErrorCode.CONFLICT, "Retrospectives are read-only when the project is completed or archived.");
    }

    private static void assertConflict(ResponseEntity<ErrorResponse> response, ErrorCode code, String message) {
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(code, response.getBody().getCode());
        assertEquals(message, response.getBody().getMessage());
    }
}
