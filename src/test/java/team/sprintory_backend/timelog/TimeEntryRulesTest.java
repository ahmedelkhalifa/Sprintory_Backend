package team.sprintory_backend.timelog;

import org.junit.jupiter.api.Test;
import team.sprintory_backend.exceptionHandler.ErrorCode;
import team.sprintory_backend.timelog.exceptions.TimeEntryException;
import team.sprintory_backend.timelog.exceptions.TimeEntryConflictException;
import team.sprintory_backend.timelog.services.TimeEntryRules;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class TimeEntryRulesTest {
    private final LocalDate today = LocalDate.of(2026, 11, 28);
    @Test void durationAndNoteBounds() {
        TimeEntryRules.validate(1, today, null, today);
        TimeEntryRules.validate(1440, today, "x".repeat(500), today);
        for (Integer minutes : new Integer[]{null, 0, -1, 1441})
            assertEquals(ErrorCode.INVALID_TIME_ENTRY, assertThrows(TimeEntryException.class,
                    () -> TimeEntryRules.validate(minutes, today, null, today)).getCode());
        assertThrows(TimeEntryException.class, () -> TimeEntryRules.validate(1, today, "x".repeat(501), today));
    }
    @Test void dateGraceAndRequiredDate() {
        TimeEntryRules.validate(1, today.minusYears(10), null, today);
        TimeEntryRules.validate(1, today.plusDays(1), null, today);
        assertThrows(TimeEntryException.class, () -> TimeEntryRules.validate(1, today.plusDays(2), null, today));
        assertThrows(TimeEntryException.class, () -> TimeEntryRules.validate(1, null, null, today));
    }
    @Test void inclusiveDailyLimitAndSnapshots() {
        TimeEntryRules.requireCapacity(1200, 240);
        assertEquals(ErrorCode.TIME_DAILY_LIMIT_EXCEEDED, assertThrows(TimeEntryConflictException.class,
                () -> TimeEntryRules.requireCapacity(1200, 241)).getCode());
        assertEquals("1h 30m on 2026-11-28", TimeEntryRules.snapshot(90, today));
        assertEquals("1m on 2026-11-28", TimeEntryRules.snapshot(1, today));
        assertEquals("24h on 2026-11-28", TimeEntryRules.snapshot(1440, today));
    }
}
