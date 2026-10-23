package team.sprintory_backend.exceptionHandler;

/**
 * A resource that does not exist or that the current user may not see. Both cases use this
 * exception so the response never reveals whether the resource exists.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}