package team.sprintory_backend.task.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.task.api.TaskSearchFilters;
import team.sprintory_backend.task.dto.CreateTaskRequest;
import team.sprintory_backend.task.dto.UpdateTaskRequest;
import team.sprintory_backend.user.models.User;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskApiServiceTest {
    private ProjectAccess access;
    private TaskApiService service;
    private User user;

    @BeforeEach
    void setUp() {
        access = mock(ProjectAccess.class);
        service = new TaskApiService(access);
        user = User.builder().id(42L).firstName("Ada").lastName("Lovelace")
                .email("ada@example.com").build();
    }

    @Test
    void allRoutesCheckPermissionsBeforeUnimplementedOperations() {
        var createRequest = new CreateTaskRequest("Fix bug", null, null, null, null, null, null);
        var updateRequest = new UpdateTaskRequest("Updated", null, null, null, null,
                null, null, null, false, false, false);
        var filters = new TaskSearchFilters(null, null, null, null, null, null);

        assertPending(() -> service.create(user, 3L, createRequest));
        assertPending(() -> service.list(user, 3L, filters, PageRequest.of(0, 20)));
        assertPending(() -> service.get(user, 3L, 5L));
        assertPending(() -> service.update(user, 3L, 5L, updateRequest));
        assertPending(() -> service.delete(user, 3L, 5L));

        verify(access, times(3)).requireActive(3L, user, ProjectPermission.EDIT_TASKS);
        verify(access, times(2)).require(3L, user, ProjectPermission.VIEW_WORK);
    }

    @Test
    void writeAccessDenialIsNotMaskedByNotImplemented() {
        doThrow(new AccessDeniedException("viewer"))
                .when(access).requireActive(3L, user, ProjectPermission.EDIT_TASKS);
        var createRequest = new CreateTaskRequest("Fix bug", null, null, null, null, null, null);
        assertThrows(AccessDeniedException.class, () -> service.create(user, 3L, createRequest));
    }

    private void assertPending(org.junit.jupiter.api.function.Executable action) {
        var exception = assertThrows(ResponseStatusException.class, action);
        assertEquals(HttpStatus.NOT_IMPLEMENTED, exception.getStatusCode());
    }
}
