package team.sprintory_backend.task.exceptions;

/** Client must explicitly confirm completing a parent with unfinished subtasks. */
public class OpenSubtasksConfirmationRequiredException extends RuntimeException {
    public OpenSubtasksConfirmationRequiredException(long count) {
        super(count + " subtasks are not Done. Retry with confirmOpenSubtasks=true to continue.");
    }
}
