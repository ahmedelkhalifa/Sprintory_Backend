package team.sprintory_backend.timelog.exceptions;

import team.sprintory_backend.exceptionHandler.ErrorCode;

public class TimeEntryException extends RuntimeException {
    public TimeEntryException(String message) {
        super(message);
    }
    public ErrorCode getCode() { return ErrorCode.INVALID_TIME_ENTRY; }
}
