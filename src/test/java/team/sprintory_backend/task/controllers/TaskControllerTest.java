package team.sprintory_backend.task.controllers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.task.api.TaskListOrder;
import team.sprintory_backend.task.api.TaskSearchFilters;
import team.sprintory_backend.task.dto.CreateTaskRequest;
import team.sprintory_backend.task.dto.MoveTaskRequest;
import team.sprintory_backend.task.dto.ReorderBacklogRequest;
import team.sprintory_backend.task.dto.TaskResponse;
import team.sprintory_backend.task.dto.UpdateTaskRequest;
import team.sprintory_backend.task.models.TaskPriority;
import team.sprintory_backend.task.models.TaskType;
import team.sprintory_backend.task.services.TaskApiService;
import team.sprintory_backend.user.models.User;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controller tests without fabricated task entities or database. */
class TaskControllerTest {
    private TaskApiService service;
    private TaskController controller;
    private User user;
    private AppUserDetails principal;
    private TaskResponse task;

    @BeforeEach
    void setUp() {
        service = mock(TaskApiService.class);
        controller = new TaskController(service);
        user = User.builder().id(17L).firstName("Ada").lastName("Lovelace")
                .email("ada@example.com").build();
        principal = new AppUserDetails(user);
        task = new TaskResponse(42L, 3L, "PMP-42", "Fix bug", TaskType.BUG,
                TaskPriority.HIGH, 5L, "To Do", null, 0, List.of("backend"),
                null, null, null, null);
    }

    @Test
    void createReturns201AndDelegatesTypedRequest() {
        var request = new CreateTaskRequest("Fix bug", TaskType.BUG, TaskPriority.HIGH,
                null, 0, List.of("backend"), null);
        when(service.create(user, 3L, request)).thenReturn(task);
        var response = controller.create(principal, 3L, request);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertSame(task, response.getBody());
    }

    @Test
    void listNormalizesAndCombinesAllFiltersAndDropsSort() {
        var inputPage = PageRequest.of(2, 15, org.springframework.data.domain.Sort.by("unknownColumn"));
        var expectedPage = PageRequest.of(2, 15);
        var filters = new TaskSearchFilters("PMP-", "BUG", "HIGH", 5L, 7L, "backend");
        var result = new PageResponse<>(List.of(task), 2, 15, 31, 3);
        when(service.list(user, 3L, filters, TaskListOrder.BOARD, expectedPage)).thenReturn(result);

        assertSame(result, controller.list(principal, 3L, " PMP- ", "bug", "high",
                5L, 7L, " backend ", "board", inputPage));
        verify(service).list(user, 3L, filters, TaskListOrder.BOARD, expectedPage);
    }

    @Test
    void readEditAndDeleteDelegateToService() {
        when(service.get(user, 3L, 42L)).thenReturn(task);
        assertSame(task, controller.get(principal, 3L, 42L));

        var request = new UpdateTaskRequest("New title", null, null, 8L,
                null, null, null, null, false, false, false);
        when(service.update(user, 3L, 42L, request)).thenReturn(task);
        assertSame(task, controller.update(principal, 3L, 42L, request));

        assertEquals(HttpStatus.NO_CONTENT, controller.delete(principal, 3L, 42L).getStatusCode());
        verify(service).delete(user, 3L, 42L);
    }

    @Test
    void boardAndBacklogMovesDelegateToService() {
        var move = new MoveTaskRequest(6L, 40L);
        when(service.moveOnBoard(user, 3L, 42L, move)).thenReturn(task);
        assertSame(task, controller.moveOnBoard(principal, 3L, 42L, move));

        var reorder = new ReorderBacklogRequest(null);
        when(service.moveInBacklog(user, 3L, 42L, reorder)).thenReturn(task);
        assertSame(task, controller.moveInBacklog(principal, 3L, 42L, reorder));
    }
}
