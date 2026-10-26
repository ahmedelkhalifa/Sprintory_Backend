package team.sprintory_backend.task.api;

import org.junit.jupiter.api.Test;
import team.sprintory_backend.exceptionHandler.BadRequestException;

import static org.junit.jupiter.api.Assertions.*;

class TaskListOrderTest {

    @Test
    void missingOrBlankMeansNewestFirst() {
        assertEquals(TaskListOrder.NEWEST, TaskListOrder.fromQuery(null));
        assertEquals(TaskListOrder.NEWEST, TaskListOrder.fromQuery("  "));
    }

    @Test
    void valuesMatchIgnoringCaseAndSpaces() {
        assertEquals(TaskListOrder.BOARD, TaskListOrder.fromQuery(" Board "));
        assertEquals(TaskListOrder.BACKLOG, TaskListOrder.fromQuery("backlog"));
        assertEquals(TaskListOrder.NEWEST, TaskListOrder.fromQuery("NEWEST"));
    }

    @Test
    void anUnknownOrderIsABadRequest() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> TaskListOrder.fromQuery("priority"));
        assertEquals("Invalid 'order': use newest, board or backlog.", e.getMessage());
    }

    @Test
    void everyOrderEndsWithAUniqueProperty() {
        assertEquals("number", TaskListOrder.NEWEST.sort().iterator().next().getProperty());
        assertNotNull(TaskListOrder.BOARD.sort().getOrderFor("id"));
        assertNotNull(TaskListOrder.BACKLOG.sort().getOrderFor("id"));
    }
}