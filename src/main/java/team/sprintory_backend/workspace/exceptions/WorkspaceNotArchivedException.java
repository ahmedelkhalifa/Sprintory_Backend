package team.sprintory_backend.workspace.exceptions;

/** A permanent delete was attempted on an active workspace; it has to be archived first. */
public class WorkspaceNotArchivedException extends RuntimeException {

    public WorkspaceNotArchivedException(String message) {
        super(message);
    }
}