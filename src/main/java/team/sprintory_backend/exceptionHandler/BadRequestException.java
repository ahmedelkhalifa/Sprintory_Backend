package team.sprintory_backend.exceptionHandler;

/**
 * A request that is well-formed but breaks a business rule the DTO cannot check, e.g. adding
 * someone to a team who is not in the workspace. The message is shown to the client.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}