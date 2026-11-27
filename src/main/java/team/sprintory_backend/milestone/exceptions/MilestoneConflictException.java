package team.sprintory_backend.milestone.exceptions;

import team.sprintory_backend.exceptionHandler.ErrorCode;

/**
 * Every 409 of the milestones (docs/milestones-docs.md §3). It carries its own code, so one
 * handler maps them all. Build it through the factories, which hold the documented messages.
 */
public class MilestoneConflictException extends RuntimeException {

    private final ErrorCode code;

    private MilestoneConflictException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }

    public static MilestoneConflictException nameTaken() {
        return new MilestoneConflictException(ErrorCode.MILESTONE_NAME_TAKEN,
                "A milestone with this name already exists in this project.");
    }

    public static MilestoneConflictException limitReached(int limit) {
        return new MilestoneConflictException(ErrorCode.MILESTONE_LIMIT_REACHED,
                "A project can have at most " + limit + " milestones.");
    }

    /** The project is completed or archived. */
    public static MilestoneConflictException readOnlyProject() {
        return new MilestoneConflictException(ErrorCode.CONFLICT,
                "Milestones are read-only when the project is completed or archived.");
    }
}
