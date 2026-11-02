package team.sprintory_backend.project.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import team.sprintory_backend.project.dto.*;
import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.project.services.ProjectBoardService;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.user.models.User;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectBoardColumnControllerTest {
    @Test
    void delegatesCrudAndReturnsExpectedStatuses() {
        ProjectBoardService service = mock(ProjectBoardService.class);
        var controller = new ProjectBoardColumnController(service);
        var user = User.builder().id(9L).email("manager@example.com").build();
        var principal = new AppUserDetails(user);
        var add = new CreateBoardColumnRequest("QA", BoardColumnCategory.IN_PROGRESS, 2L);
        assertEquals(HttpStatus.CREATED, controller.add(principal, 1L, add).getStatusCode());
        verify(service).add(user, 1L, add);
        var update = new UpdateBoardColumnRequest("Review", null, 3, null);
        controller.update(principal, 1L, 3L, update);
        verify(service).update(user, 1L, 3L, update);
        var reorder = new ReorderBoardColumnsRequest(List.of(1L, 3L, 2L));
        controller.reorder(principal, 1L, reorder);
        verify(service).reorder(user, 1L, reorder);
        var delete = new DeleteBoardColumnRequest(2L);
        assertEquals(HttpStatus.NO_CONTENT, controller.delete(principal, 1L, 3L, delete).getStatusCode());
        verify(service).delete(user, 1L, 3L, delete);
    }
}
