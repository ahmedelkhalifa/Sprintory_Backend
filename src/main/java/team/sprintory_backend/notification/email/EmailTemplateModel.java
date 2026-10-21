package team.sprintory_backend.notification.email;

/** Typed data passed to the HTML renderer; text is escaped by Thymeleaf. */
public record EmailTemplateModel(
        EmailTemplate type,
        String subject,
        String recipientName,
        String heading,
        String message,
        String actionLabel,
        String actionUrl,
        String note
) {
}
