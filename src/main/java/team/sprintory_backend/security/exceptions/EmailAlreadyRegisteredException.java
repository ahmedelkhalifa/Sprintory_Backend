package team.sprintory_backend.security.exceptions;

/** Registration for an email that already has a verified password account. */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException(String message) {
        super(message);
    }
}