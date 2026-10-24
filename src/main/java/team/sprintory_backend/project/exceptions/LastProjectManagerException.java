package team.sprintory_backend.project.exceptions;

/**
 * A change would leave a project without a Project Manager (story 6). The message says which
 * rule or project, so the handler passes it through.
 */
public class LastProjectManagerException extends RuntimeException {

    public LastProjectManagerException(String message) {
        super(message);
    }
}