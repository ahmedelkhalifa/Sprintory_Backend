package team.sprintory_backend.sprint.services;

import team.sprintory_backend.sprint.dto.BurndownResponse;
import team.sprintory_backend.sprint.models.*;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PMP-42: builds the burndown chart of a started sprint from its daily totals
 * (docs/burndown-docs.md §3). Pure: no repositories, and "today" is passed in.
 */
public final class BurndownCalculator {

    /** Today's actual more than this share of the committed amount off the ideal line is BEHIND or AHEAD. */
    static final double THRESHOLD = 0.10;

    private BurndownCalculator() {}

    /**
     * @param committed the committed amount in {@code unit}; null for a sprint not started in Sprintory
     * @param days      the saved totals by day (UTC)
     * @param live      today's totals of an ACTIVE sprint, read now; null for an ended sprint
     */
    public static BurndownResponse calculate(Sprint sprint, Long committed, Map<LocalDate, SprintTotals> days,
                                             SprintTotals live, LocalDate today, BurndownUnit unit, BurndownDays mode) {
        boolean active = sprint.getStatus() == SprintStatus.ACTIVE;
        LocalDate start = sprint.getStartDate();
        LocalDate end = sprint.getEndDate();
        LocalDate endedOn = active ? null : LocalDate.ofInstant(sprint.getCompletedAt(), ZoneOffset.UTC);
        LocalDate lastDay = active ? today : endedOn;
        LocalDate axisEnd = lastDay.isAfter(end) ? lastDay : end;
        // A weekend-only sprint has no working day: fall back to calendar days.
        boolean working = mode == BurndownDays.WORKING && SprintCapacityCalculator.workingDays(start, end) > 0;

        Map<LocalDate, SprintTotals> data = new HashMap<>(days);
        if (live != null) data.put(today, live);
        // A sprint started before its start date begins on the day it started; the ideal line is flat until startDate.
        LocalDate firstData = data.keySet().stream().min(Comparator.naturalOrder()).orElse(start);
        LocalDate axisStart = firstData.isBefore(start) ? firstData : start;

        // Index 0 is the Start point: ideal = actual = committed (actual set below, once we know there is data).
        List<LocalDate> dates = new ArrayList<>(List.of(axisStart));
        List<Double> ideal = new ArrayList<>();
        ideal.add(committed == null ? null : committed.doubleValue());
        List<Long> actual = new ArrayList<>();
        actual.add(null);
        List<Long> scopeChanges = new ArrayList<>();
        scopeChanges.add(null);

        SprintTotals current = null;   // the latest totals on or before the day: a missing day repeats it
        SprintTotals latest = null;    // the totals of the last actual day
        Long previousTotal = committed;
        for (LocalDate day = axisStart; !day.isAfter(axisEnd); day = day.plusDays(1)) {
            dates.add(day);
            ideal.add(idealAt(committed, start, end, day, working));
            if (data.containsKey(day)) current = data.get(day);
            if (day.isAfter(lastDay) || current == null) {
                actual.add(null);
                scopeChanges.add(null);
                previousTotal = null;
                continue;
            }
            long total = unit.total(current);
            actual.add(unit.remaining(current));
            scopeChanges.add(previousTotal == null ? null : total - previousTotal);
            previousTotal = total;
            latest = current;
        }

        boolean hasDailyData = latest != null;
        if (hasDailyData) actual.set(0, committed);

        BurndownStatus status = null;
        if (active && committed != null && hasDailyData) {
            double gap = unit.remaining(latest) - idealAt(committed, start, end, today, working);
            double margin = THRESHOLD * committed;
            status = gap > margin ? BurndownStatus.BEHIND : gap < -margin ? BurndownStatus.AHEAD : BurndownStatus.ON_TRACK;
        }

        int daysLeft = active && today.isBefore(end) ? elapsed(today.plusDays(1), end, working) : 0;
        Long currentScope = hasDailyData ? unit.total(latest) : null;
        Long remaining = hasDailyData ? unit.remaining(latest) : null;
        BurndownResponse.Summary summary = new BurndownResponse.Summary(
                committed,
                currentScope,
                committed == null || currentScope == null ? null : currentScope - committed,
                hasDailyData ? currentScope - remaining : null,
                remaining,
                daysLeft,
                hasDailyData ? latest.unestimatedTasks() : null);

        return new BurndownResponse(sprint.getId(), sprint.getName(), sprint.getStatus(), start, end, endedOn, today,
                unit, mode, hasDailyData, dates, ideal, actual, scopeChanges, summary, status);
    }

    /** The ideal remaining work at the end of {@code day}: 0 from {@code end} on, rounded to 2 decimals. */
    private static Double idealAt(Long committed, LocalDate start, LocalDate end, LocalDate day, boolean working) {
        if (committed == null) return null;
        double share = (double) elapsed(start, day.isAfter(end) ? end : day, working) / elapsed(start, end, working);
        return Math.round(committed * (1 - share) * 100) / 100.0;
    }

    /** Days in {@code [from, to]} the ideal line descends on. */
    private static int elapsed(LocalDate from, LocalDate to, boolean working) {
        if (working) return SprintCapacityCalculator.workingDays(from, to);
        return (int) Math.max(0, ChronoUnit.DAYS.between(from, to) + 1);
    }
}
