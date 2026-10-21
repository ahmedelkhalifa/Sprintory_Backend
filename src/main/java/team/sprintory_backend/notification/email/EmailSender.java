package team.sprintory_backend.notification.email;

/** Delivery boundary, separated from HTML template rendering for isolated tests. */
public interface EmailSender {
    void send(String recipientEmail, RenderedEmail email);
}
