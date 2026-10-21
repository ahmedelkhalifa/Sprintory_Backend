package team.sprintory_backend.notification.email;

/** A fully rendered, transport-independent multipart/alternative email. */
public record RenderedEmail(String subject, String plainText, String html) {
}
