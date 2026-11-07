package team.sprintory_backend.task.api;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import team.sprintory_backend.exceptionHandler.BadRequestException;

import static org.junit.jupiter.api.Assertions.*;

class TaskHistoryOrderTest {

    @Test
    void missingOrBlankMeansNewestFirst() {
        assertEquals(TaskHistoryOrder.NEWEST, TaskHistoryOrder.fromQuery(null));
        assertEquals(TaskHistoryOrder.NEWEST, TaskHistoryOrder.fromQuery("  "));
    }

    @Test
    void valuesMatchIgnoringCaseAndSpaces() {
        assertEquals(TaskHistoryOrder.OLDEST, TaskHistoryOrder.fromQuery(" Oldest "));
        assertEquals(TaskHistoryOrder.NEWEST, TaskHistoryOrder.fromQuery("NEWEST"));
    }

    @Test
    void anUnknownOrderIsABadRequest() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> TaskHistoryOrder.fromQuery("occurredAt,asc"));
        assertEquals("Invalid 'order': use newest or oldest.", e.getMessage());
    }

    @Test
    void bothOrdersSortByTimeThenIdInTheSameDirection() {
        assertEquals(Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")), TaskHistoryOrder.NEWEST.sort());
        assertEquals(Sort.by(Sort.Order.asc("occurredAt"), Sort.Order.asc("id")), TaskHistoryOrder.OLDEST.sort());
    }
}