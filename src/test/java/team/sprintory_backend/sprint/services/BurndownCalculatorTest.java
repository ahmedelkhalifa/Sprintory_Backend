package team.sprintory_backend.sprint.services;

import org.junit.jupiter.api.Test;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.sprint.dto.BurndownResponse;
import team.sprintory_backend.sprint.models.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** PMP-42: the burndown rules, on the sprint of docs/burndown-docs.md §3.1 (Mon 30 Nov – Fri 11 Dec 2026, 30 points). */
class BurndownCalculatorTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 30);
    private static final LocalDate TUE = MON.plusDays(1);
    private static final LocalDate WED = MON.plusDays(2);
    private static final LocalDate THU = MON.plusDays(3);

    @Test
    void followsTheDocExample() {
        // AC2: 25 left at the end of day 2. AC10: 3 per working day, flat at the weekend.
        // AC14: today's stale snapshot is replaced by the live totals. A 3-point task added on Wednesday.
        Map<LocalDate, SprintTotals> days = Map.of(
                MON, points(30, 30), TUE, points(30, 25), WED, points(30, 30));

        BurndownResponse r = BurndownCalculator.calculate(sprint(SprintStatus.ACTIVE), 30L, days,
                points(33, 26), WED, BurndownUnit.POINTS, BurndownDays.WORKING);

        assertEquals(List.of(30.0, 27.0, 24.0, 21.0, 18.0, 15.0, 15.0, 15.0, 12.0, 9.0, 6.0, 3.0, 0.0), r.ideal());
        assertEquals(Arrays.asList(30L, 30L, 25L, 26L, null, null, null, null, null, null, null, null, null), r.actual());
        assertEquals(Arrays.asList(null, 0L, 0L, 3L, null, null, null, null, null, null, null, null, null), r.scopeChanges());
        assertEquals(new BurndownResponse.Summary(30L, 33L, 3L, 7L, 26L, 7, 1L), r.summary());
        assertEquals(BurndownStatus.BEHIND, r.status());
    }

    @Test
    void calendarDaysDescendEveryDay() {
        BurndownResponse r = BurndownCalculator.calculate(sprint(SprintStatus.ACTIVE), 30L, Map.of(),
                points(30, 30), MON, BurndownUnit.POINTS, BurndownDays.CALENDAR);

        assertEquals(27.5, r.ideal().get(1));
        assertEquals(12.5, r.ideal().get(7));   // Sunday 6 Dec still descends
        assertEquals(0.0, r.ideal().get(12));
    }

    @Test
    void statusUsesTenPercentOfTheCommittedAmount() {
        // AC13: Wednesday's ideal is 21 and 10 % of 30 is 3.
        assertEquals(BurndownStatus.ON_TRACK, statusOnWednesday(24));
        assertEquals(BurndownStatus.ON_TRACK, statusOnWednesday(18));
        assertEquals(BurndownStatus.AHEAD, statusOnWednesday(17));
    }

    @Test
    void endedSprintStopsOnItsClosingDayAndRepeatsAMissingDay() {
        // AC19: no snapshot on Wednesday, so Wednesday repeats Tuesday.
        Sprint sprint = sprint(SprintStatus.COMPLETED);
        sprint.setCompletedAt(Instant.parse("2026-12-03T15:00:00Z"));
        Map<LocalDate, SprintTotals> days = Map.of(MON, points(30, 30), TUE, points(30, 25), THU, points(30, 20));

        BurndownResponse r = BurndownCalculator.calculate(sprint, 30L, days, null,
                LocalDate.of(2026, 12, 10), BurndownUnit.POINTS, BurndownDays.WORKING);

        assertEquals(Arrays.asList(30L, 30L, 25L, 25L, 20L, null), r.actual().subList(0, 6));
        assertEquals(THU, r.endedOn());
        assertNull(r.status());
        assertEquals(0, r.summary().daysLeft());
    }

    @Test
    void sprintWithoutSnapshotsHasNoDailyData() {
        Sprint sprint = sprint(SprintStatus.COMPLETED);
        sprint.setCompletedAt(Instant.parse("2026-12-11T15:00:00Z"));

        BurndownResponse r = BurndownCalculator.calculate(sprint, 30L, Map.of(), null,
                LocalDate.of(2026, 12, 20), BurndownUnit.POINTS, BurndownDays.WORKING);

        assertFalse(r.hasDailyData());
        assertTrue(r.actual().stream().allMatch(Objects::isNull));
        assertEquals(new BurndownResponse.Summary(30L, null, null, null, null, 0, null), r.summary());
    }

    @Test
    void queryOptionsDefaultAndRefuseUnknownValues() {
        assertEquals(BurndownUnit.POINTS, BurndownUnit.fromQuery(null));
        assertEquals(BurndownDays.CALENDAR, BurndownDays.fromQuery("Calendar"));
        BadRequestException e = assertThrows(BadRequestException.class, () -> BurndownUnit.fromQuery("hours"));
        assertEquals("Invalid 'unit': use points or tasks.", e.getMessage());
    }

    private static BurndownStatus statusOnWednesday(int remaining) {
        return BurndownCalculator.calculate(sprint(SprintStatus.ACTIVE), 30L, Map.of(),
                points(30, remaining), WED, BurndownUnit.POINTS, BurndownDays.WORKING).status();
    }

    private static Sprint sprint(SprintStatus status) {
        Sprint sprint = new Sprint(null, 4, "MOB Sprint 4", "Goal", null, MON, LocalDate.of(2026, 12, 11));
        sprint.setStatus(status);
        return sprint;
    }

    private static SprintTotals points(long total, long remaining) {
        return new SprintTotals(total, remaining, 0, 0, 1);
    }
}
