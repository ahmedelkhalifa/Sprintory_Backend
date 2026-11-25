package team.sprintory_backend.retro.exceptions;

import team.sprintory_backend.exceptionHandler.ErrorCode;

/**
 * Every 409 of the retrospectives (docs/retrospectives-docs.md §5). It carries its own code, so
 * one handler maps them all. Build it through the factories, which hold the documented messages.
 */
public class RetroConflictException extends RuntimeException {

    private final ErrorCode code;

    private RetroConflictException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }

    public static RetroConflictException sprintNotEnded() {
        return new RetroConflictException(ErrorCode.RETRO_SPRINT_NOT_ENDED,
                "The sprint must be completed or cancelled first.");
    }

    public static RetroConflictException closed() {
        return new RetroConflictException(ErrorCode.RETRO_CLOSED, "This retrospective is closed.");
    }

    public static RetroConflictException alreadyVoted() {
        return new RetroConflictException(ErrorCode.ALREADY_VOTED, "You already voted on this card.");
    }

    public static RetroConflictException noVotesLeft() {
        return new RetroConflictException(ErrorCode.NO_VOTES_LEFT, "No votes left.");
    }

    /** The project is completed or archived. */
    public static RetroConflictException readOnlyProject() {
        return new RetroConflictException(ErrorCode.CONFLICT,
                "Retrospectives are read-only when the project is completed or archived.");
    }
}
