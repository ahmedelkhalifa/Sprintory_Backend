package team.sprintory_backend.sprint.models;

import team.sprintory_backend.exceptionHandler.BadRequestException;

import java.util.Locale;

/** The {@code ?days=} of the burndown (docs/burndown-docs.md §2): which days the ideal line descends on. */
public enum BurndownDays {
    /** Monday to Friday; flat on weekends. */
    WORKING,
    /** Every day. */
    CALENDAR;

    /** Null or blank means {@link #WORKING}; matching ignores case. Anything else is a 400. */
    public static BurndownDays fromQuery(String value) {
        if (value == null || value.isBlank()) return WORKING;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid 'days': use working or calendar.");
        }
    }
}
