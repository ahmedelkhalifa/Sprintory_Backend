package team.sprintory_backend.notification.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.notification.dto.NotificationResponse;
import team.sprintory_backend.notification.dto.NotificationTargetResponse;
import team.sprintory_backend.notification.dto.UnreadCountResponse;
import team.sprintory_backend.notification.services.NotificationService;
import team.sprintory_backend.notification.services.NotificationStreamService;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.user.models.User;

/** The controller only parses the query and passes the caller on; the rules are tested in the service. */
class NotificationControllerTest {

    private NotificationService service;
    private NotificationStreamService streamService;
    private NotificationController controller;
    private User user;
    private AppUserDetails principal;

    @BeforeEach
    void setUp() {
        service = mock(NotificationService.class);
        streamService = mock(NotificationStreamService.class);
        controller = new NotificationController(service, streamService);
        user = User.builder().id(17L).firstName("Ada").lastName("Lovelace").email("ada@example.com").build();
        principal = new AppUserDetails(user);
    }

    @Test
    void listPassesTheFiltersAndDropsTheClientSort() {
        PageResponse<NotificationResponse> page = new PageResponse<>(List.of(), 1, 10, 0, 0);
        when(service.list(user, true, 4L, PageRequest.of(1, 10))).thenReturn(page);

        assertSame(page, controller.list(principal, true, 4L, PageRequest.of(1, 10, Sort.by("message"))));
    }

    @Test
    void aMissingUnreadOnlyMeansAll() {
        PageResponse<NotificationResponse> page = new PageResponse<>(List.of(), 0, 20, 0, 0);
        when(service.list(user, false, null, PageRequest.of(0, 20))).thenReturn(page);

        assertSame(page, controller.list(principal, null, null, PageRequest.of(0, 20)));
    }

    @Test
    void aNonPositiveWorkspaceIdIsRefusedBeforeTheServiceIsCalled() {
        assertThrows(BadRequestException.class, () -> controller.list(principal, null, 0L, PageRequest.of(0, 20)));
        verifyNoInteractions(service);
    }

    @Test
    void unreadCountOpenAndReadAllAreTheCallers() {
        UnreadCountResponse three = new UnreadCountResponse(3);
        UnreadCountResponse zero = new UnreadCountResponse(0);
        NotificationTargetResponse target = NotificationTargetResponse.none();
        when(service.unreadCount(user)).thenReturn(three);
        when(service.open(user, 901L)).thenReturn(target);
        when(service.readAll(user)).thenReturn(zero);

        assertSame(three, controller.unreadCount(principal));
        assertSame(target, controller.open(principal, 901L));
        assertSame(zero, controller.readAll(principal));
    }

    @Test
    void theStreamIsTheCallersAndTellsProxiesNotToBufferOrCache() {
        SseEmitter emitter = new SseEmitter();
        when(streamService.subscribe(user)).thenReturn(emitter);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertSame(emitter, controller.stream(principal, response));
        assertEquals("no", response.getHeader("X-Accel-Buffering"));
        assertEquals("no-cache", response.getHeader("Cache-Control"));
    }
}