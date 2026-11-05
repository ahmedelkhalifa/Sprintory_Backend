package team.sprintory_backend.comment.controllers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import team.sprintory_backend.comment.dto.CommentRequest;
import team.sprintory_backend.comment.dto.CommentResponse;
import team.sprintory_backend.comment.services.CommentService;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.user.models.User;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The controller only passes the caller and the ids on; the rules are tested in the service. */
class CommentControllerTest {

    private CommentService service;
    private CommentController controller;
    private User user;
    private AppUserDetails principal;
    private final CommentRequest request = new CommentRequest("{\"type\":\"doc\"}");

    @BeforeEach
    void setUp() {
        service = mock(CommentService.class);
        controller = new CommentController(service);
        user = User.builder().id(42L).firstName("Ada").lastName("Lovelace").email("ada@example.com").build();
        principal = new AppUserDetails(user);
    }

    @Test
    void listDropsAnySortAndAsksTheService() {
        PageResponse<CommentResponse> page = new PageResponse<>(List.of(), 1, 10, 0, 0);
        when(service.list(user, 3L, 5L, PageRequest.of(1, 10))).thenReturn(page);

        assertSame(page, controller.list(principal, 3L, 5L, PageRequest.of(1, 10, Sort.by("body"))));
    }

    @Test
    void postAnswers201WithTheComment() {
        CommentResponse comment = comment();
        when(service.create(user, 3L, 5L, request)).thenReturn(comment);

        ResponseEntity<CommentResponse> response = controller.create(principal, 3L, 5L, request);

        assertEquals(201, response.getStatusCode().value());
        assertSame(comment, response.getBody());
    }

    @Test
    void patchAsksTheService() {
        CommentResponse comment = comment();
        when(service.update(user, 3L, 5L, 77L, request)).thenReturn(comment);

        assertSame(comment, controller.update(principal, 3L, 5L, 77L, request));
    }

    @Test
    void deleteAnswers204WithoutABody() {
        ResponseEntity<Void> response = controller.delete(principal, 3L, 5L, 77L);

        verify(service).delete(user, 3L, 5L, 77L);
        assertEquals(204, response.getStatusCode().value());
        assertNull(response.getBody());
    }

    private static CommentResponse comment() {
        return new CommentResponse(77L, 5L, null, null, List.of(), null, null, false, null, true, true);
    }
}