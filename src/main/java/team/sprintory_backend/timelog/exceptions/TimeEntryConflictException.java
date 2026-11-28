package team.sprintory_backend.timelog.exceptions;

import team.sprintory_backend.exceptionHandler.ErrorCode;

/** State-dependent conflicts, distinct from invalid time-entry input. */
public class TimeEntryConflictException extends RuntimeException {
    private final ErrorCode code;

    private TimeEntryConflictException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() { return code; }

    public static TimeEntryConflictException dailyLimitExceeded() {
        return new TimeEntryConflictException(ErrorCode.TIME_DAILY_LIMIT_EXCEEDED,
                "Total for this day would exceed 24 hours.");
    }

    public static TimeEntryConflictException readOnlyProject() {
        return new TimeEntryConflictException(ErrorCode.CONFLICT,
                "Time entries are read-only when the project is completed or archived.");
    }
}
