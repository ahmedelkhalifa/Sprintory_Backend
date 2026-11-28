package team.sprintory_backend.timelog.exceptions;

import team.sprintory_backend.exceptionHandler.ErrorCode;

public class TimeEntryException extends RuntimeException {
    private final ErrorCode code;
    public TimeEntryException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }
    public ErrorCode getCode() { return code; }
}
