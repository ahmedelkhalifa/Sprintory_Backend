package team.sprintory_backend.schedule.api;

import org.junit.jupiter.api.Test;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.schedule.dto.ScheduleTask;
import team.sprintory_backend.task.models.TaskType;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleFiltersTest {
    private final LocalDate start = LocalDate.of(2026, 11, 10);
    private final LocalDate due = start.plusDays(2);
    private final ScheduleTask row = new ScheduleTask(1L, "PMP-1", "Task", start, due,
            BoardColumnCategory.DONE, 2L, "Shipped", TaskType.BUG, 3L, "Ada", 4L, 5L);

    @Test void allFiltersAndInclusiveWindow() {
        assertTrue(new ScheduleFilters(3L, 2L, BoardColumnCategory.DONE, 5L, 4L,
                TaskType.BUG, false, due, due).matches(row));
        assertFalse(new ScheduleFilters(3L, 2L, BoardColumnCategory.DONE, 5L, 4L,
                TaskType.BUG, true, due, due).matches(row));
        assertFalse(new ScheduleFilters(null, null, null, 99L, null,
                null, false, null, null).matches(row));
        assertFalse(new ScheduleFilters(null, null, null, null, null,
                null, false, due.plusDays(1), null).matches(row));
        assertFalse(new ScheduleFilters(null, null, null, null, null,
                null, false, null, start.minusDays(1)).matches(row));
        assertTrue(new ScheduleFilters(null, null, null, null, null,
                null, false, null, start).matches(row));
    }

    @Test void rejectsInvalidWindowAndIds() {
        assertThrows(BadRequestException.class, () -> new ScheduleFilters(null, null, null, null, null,
                null, null, due, start).validate());
        assertThrows(BadRequestException.class, () -> new ScheduleFilters(0L, null, null, null, null,
                null, null, null, null).validate());
    }
}
