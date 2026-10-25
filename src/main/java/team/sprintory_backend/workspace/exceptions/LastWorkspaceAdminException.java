package team.sprintory_backend.workspace.exceptions;

/** A role change or removal would leave a workspace without an admin (story 6). */
public class LastWorkspaceAdminException extends RuntimeException {

    public LastWorkspaceAdminException(String message) {
        super(message);
    }
}