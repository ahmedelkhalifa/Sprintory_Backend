package team.sprintory_backend.notification.controllers;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import team.sprintory_backend.notification.dto.NotificationPreferencesResponse;
import team.sprintory_backend.notification.dto.UpdateNotificationPreferencesRequest;
import team.sprintory_backend.notification.models.NotificationCategory;
import team.sprintory_backend.notification.services.NotificationPreferenceService;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.user.models.User;

/** The controller only passes the caller and the body on; the rules are tested in the service. */
class NotificationPreferenceControllerTest {

    private NotificationPreferenceService service;
    private NotificationPreferenceController controller;
    private User user;
    private AppUserDetails principal;

    @BeforeEach
    void setUp() {
        service = mock(NotificationPreferenceService.class);
        controller = new NotificationPreferenceController(service);
        user = User.builder().id(17L).firstName("Ada").lastName("Lovelace").email("ada@example.com").build();
        principal = new AppUserDetails(user);
    }

    @Test
    void getReturnsTheCallersMatrix() {
        NotificationPreferencesResponse matrix = new NotificationPreferencesResponse(List.of());
        when(service.get(user)).thenReturn(matrix);

        assertSame(matrix, controller.get(principal));
    }

    @Test
    void updatePassesTheCallerAndTheBody() {
        UpdateNotificationPreferencesRequest body = new UpdateNotificationPreferencesRequest(List.of(
                new UpdateNotificationPreferencesRequest.Change(NotificationCategory.COMMENT, null, true)));
        NotificationPreferencesResponse matrix = new NotificationPreferencesResponse(List.of());
        when(service.update(user, body)).thenReturn(matrix);

        assertSame(matrix, controller.update(principal, body));
    }
}