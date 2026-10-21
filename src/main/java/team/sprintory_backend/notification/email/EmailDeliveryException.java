package team.sprintory_backend.notification.email;

/** Internal delivery failure; never expose SMTP configuration or credentials to API clients. */
public class EmailDeliveryException extends RuntimeException {
    public EmailDeliveryException(String message) {
        super(message);
    }

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
