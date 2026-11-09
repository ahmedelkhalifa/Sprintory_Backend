package team.sprintory_backend.sprint.exceptions;
public class SprintAlreadyActiveException extends RuntimeException {
    public SprintAlreadyActiveException() { super("Another sprint is already active."); }
}
