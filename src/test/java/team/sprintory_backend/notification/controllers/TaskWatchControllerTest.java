package team.sprintory_backend.notification.controllers;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import team.sprintory_backend.notification.dto.WatchResponse;
import team.sprintory_backend.notification.services.TaskWatcherService;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.user.models.User;

/** The controller only passes the caller and the ids on; access is tested in the service. */
class TaskWatchControllerTest {

    private TaskWatcherService service;
    private TaskWatchController controller;
    private User user;
    private AppUserDetails principal;

    @BeforeEach
    void setUp() {
        service = mock(TaskWatcherService.class);
        controller = new TaskWatchController(service);
        user = User.builder().id(42L).firstName("Ada").lastName("Lovelace").email("ada@example.com").build();
        principal = new AppUserDetails(user);
    }

    @Test
    void getAsksTheService() {
        WatchResponse response = new WatchResponse(true);
        when(service.isWatching(user, 3L, 500L)).thenReturn(response);

        assertSame(response, controller.get(principal, 3L, 500L));
    }

    @Test
    void putWatches() {
        WatchResponse response = new WatchResponse(true);
        when(service.watch(user, 3L, 500L)).thenReturn(response);

        assertSame(response, controller.watch(principal, 3L, 500L));
    }

    @Test
    void deleteUnwatches() {
        WatchResponse response = new WatchResponse(false);
        when(service.unwatch(user, 3L, 500L)).thenReturn(response);

        assertSame(response, controller.unwatch(principal, 3L, 500L));
    }
}