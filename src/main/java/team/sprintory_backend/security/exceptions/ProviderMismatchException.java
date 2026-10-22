package team.sprintory_backend.security.exceptions;

/** Email/password login or registration for an email that belongs to a Google account. */
public class ProviderMismatchException extends RuntimeException {

    public ProviderMismatchException(String message) {
        super(message);
    }
}