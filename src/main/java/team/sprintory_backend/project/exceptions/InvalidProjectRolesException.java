package team.sprintory_backend.project.exceptions;

/**
 * A set of project roles breaks a role rule: empty, Viewer combined with another role, or a role
 * the project's template does not offer. The message names the rule.
 */
public class InvalidProjectRolesException extends RuntimeException {

    public InvalidProjectRolesException(String message) {
        super(message);
    }
}