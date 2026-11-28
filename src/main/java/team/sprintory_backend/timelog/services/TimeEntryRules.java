package team.sprintory_backend.timelog.services;

import team.sprintory_backend.timelog.exceptions.TimeEntryConflictException;
import team.sprintory_backend.timelog.exceptions.TimeEntryException;
import java.time.LocalDate;

/** Pure validation; the API accepts integer minutes, not formatted strings. */
public final class TimeEntryRules {
    public static final int DAILY_LIMIT = 1440;
    private TimeEntryRules() { }

    public static void validate(Integer minutes, LocalDate date, String note, LocalDate today) {
        if (minutes == null || minutes < 1 || minutes > DAILY_LIMIT)
            throw invalid("Duration must be between 1 and 1440 minutes.");
        if (date == null || date.isAfter(today.plusDays(1)))
            throw invalid("Work date must be set and cannot be more than one day ahead of server today.");
        if (note != null && note.length() > 500)
            throw invalid("Note must be at most 500 characters.");
    }

    public static void requireCapacity(long existingMinutes, int minutes) {
        if (existingMinutes > DAILY_LIMIT - minutes)
            throw TimeEntryConflictException.dailyLimitExceeded();
    }

    public static String snapshot(int minutes, LocalDate date) {
        int hours = minutes / 60, remainder = minutes % 60;
        String duration = hours == 0 ? remainder + "m" : hours + "h" + (remainder == 0 ? "" : " " + remainder + "m");
        return duration + " on " + date;
    }

    private static TimeEntryException invalid(String message) {
        return new TimeEntryException(message);
    }
}
