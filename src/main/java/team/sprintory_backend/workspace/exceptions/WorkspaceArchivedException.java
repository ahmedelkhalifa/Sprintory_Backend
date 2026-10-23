package team.sprintory_backend.workspace.exceptions;

/** A change was attempted on an archived workspace, which is read-only until it is restored. */
public class WorkspaceArchivedException extends RuntimeException {

    public WorkspaceArchivedException(String message) {
        super(message);
    }
}