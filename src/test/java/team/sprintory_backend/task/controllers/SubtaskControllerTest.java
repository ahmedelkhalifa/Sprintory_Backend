package team.sprintory_backend.task.controllers;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.task.dto.CreateSubtaskRequest;
import team.sprintory_backend.task.dto.UpdateSubtaskRequest;
import team.sprintory_backend.task.services.SubtaskService;
import team.sprintory_backend.user.models.User;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubtaskControllerTest {
    @Test
    void delegatesCreateListAndUpdate() {
        SubtaskService service = mock(SubtaskService.class);
        SubtaskController controller = new SubtaskController(service);
        User user = User.builder().id(5L).build();
        AppUserDetails principal = new AppUserDetails(user);
        CreateSubtaskRequest create = new CreateSubtaskRequest("Child", null, null, 60);
        assertEquals(HttpStatus.CREATED, controller.create(principal, 2L, 3L, create).getStatusCode());
        verify(service).create(user, 2L, 3L, create);
        when(service.list(user, 2L, 3L)).thenReturn(List.of());
        assertEquals(List.of(), controller.list(principal, 2L, 3L));
        UpdateSubtaskRequest update = new UpdateSubtaskRequest("Changed", null, null, null, false, false);
        controller.update(principal, 2L, 3L, 9L, update);
        verify(service).update(user, 2L, 3L, 9L, update);
    }
}
