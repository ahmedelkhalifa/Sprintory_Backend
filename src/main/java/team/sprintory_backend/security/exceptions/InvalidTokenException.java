package team.sprintory_backend.security.exceptions;

/** A refresh token, activation link or reset link that is missing, unknown, expired or already used. */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }
}