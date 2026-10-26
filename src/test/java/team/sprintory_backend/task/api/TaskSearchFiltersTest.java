package team.sprintory_backend.task.api;

import org.junit.jupiter.api.Test;
import team.sprintory_backend.exceptionHandler.BadRequestException;

import static org.junit.jupiter.api.Assertions.*;

class TaskSearchFiltersTest {

    @Test
    void allFiltersAbsentMeansUnfiltered() {
        TaskSearchFilters filters = TaskSearchFilters.fromQuery(null, null, null, null, null, null);
        assertTrue(filters.isUnfiltered());
        assertNull(filters.q());
    }

    @Test
    void blankFiltersAreAbsent() {
        TaskSearchFilters filters = TaskSearchFilters.fromQuery(" ", " ", "\t", null, null, "   ");
        assertTrue(filters.isUnfiltered());
    }

    @Test
    void combinesFiltersWithoutDroppingAny() {
        TaskSearchFilters filters = TaskSearchFilters.fromQuery(
                " PMP-42 ", " bug ", " highest ", 9L, 7L, " backend ");
        assertAll(
                () -> assertEquals("PMP-42", filters.q()),
                () -> assertEquals("BUG", filters.type()),
                () -> assertEquals("HIGHEST", filters.priority()),
                () -> assertEquals(9L, filters.statusId()),
                () -> assertEquals(7L, filters.assigneeId()),
                () -> assertEquals("backend", filters.label()),
                () -> assertFalse(filters.isUnfiltered())
        );
    }

    @Test
    void acceptsEveryTaskType() {
        for (String type : new String[]{"STORY", "TASK", "BUG"}) {
            assertEquals(type, TaskSearchFilters.fromQuery(null, type, null, null, null, null).type());
        }
    }

    @Test
    void acceptsEveryPriority() {
        for (String priority : new String[]{"HIGHEST", "HIGH", "MEDIUM", "LOW", "LOWEST"}) {
            assertEquals(priority,
                    TaskSearchFilters.fromQuery(null, null, priority, null, null, null).priority());
        }
    }

    @Test
    void rejectsUnknownType() {
        assertThrows(BadRequestException.class,
                () -> TaskSearchFilters.fromQuery(null, "EPIC", null, null, null, null));
    }

    @Test
    void rejectsUnknownPriority() {
        assertThrows(BadRequestException.class,
                () -> TaskSearchFilters.fromQuery(null, null, "URGENT", null, null, null));
    }

    @Test
    void rejectsZeroOrNegativeStatusId() {
        assertThrows(BadRequestException.class,
                () -> TaskSearchFilters.fromQuery(null, null, null, 0L, null, null));
        assertThrows(BadRequestException.class,
                () -> TaskSearchFilters.fromQuery(null, null, null, -2L, null, null));
    }

    @Test
    void rejectsZeroOrNegativeAssigneeId() {
        assertThrows(BadRequestException.class,
                () -> TaskSearchFilters.fromQuery(null, null, null, null, 0L, null));
        assertThrows(BadRequestException.class,
                () -> TaskSearchFilters.fromQuery(null, null, null, null, -1L, null));
    }

    @Test
    void labelRemainsFreeTextAndCaseSensitive() {
        TaskSearchFilters filters = TaskSearchFilters.fromQuery(null, null, null, null, null, " R09-Backend ");
        assertEquals("R09-Backend", filters.label());
    }

    @Test
    void queryPreservesCaseAndSpecialCharactersForLiteralMatching() {
        TaskSearchFilters filters = TaskSearchFilters.fromQuery("  PMP_% ", null, null, null, null, null);
        assertEquals("PMP_%", filters.q());
    }
}
