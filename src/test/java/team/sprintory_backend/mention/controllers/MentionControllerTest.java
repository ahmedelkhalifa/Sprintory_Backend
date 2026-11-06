package team.sprintory_backend.mention.controllers;

import org.junit.jupiter.api.Test;
import team.sprintory_backend.mention.dto.MentionablesResponse;
import team.sprintory_backend.mention.services.MentionService;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.user.models.User;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The controller only passes the caller, the project and the search on; the rules are tested in the service. */
class MentionControllerTest {

    @Test
    void mentionablesAsksTheService() {
        MentionService service = mock(MentionService.class);
        MentionController controller = new MentionController(service);
        User user = User.builder().id(42L).firstName("Ada").lastName("Lovelace").email("ada@example.com").build();
        MentionablesResponse response = new MentionablesResponse(List.of(), List.of());
        when(service.mentionables(user, 3L, "da")).thenReturn(response);

        assertSame(response, controller.mentionables(new AppUserDetails(user), 3L, "da"));
    }
}