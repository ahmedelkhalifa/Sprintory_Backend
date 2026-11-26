package team.sprintory_backend.task.exceptions;

/** Dates are immutable after a project is completed or archived. */
public class TaskDatesReadOnlyException extends RuntimeException {
    public TaskDatesReadOnlyException() { super("Task dates are read-only in this project state."); }
}
