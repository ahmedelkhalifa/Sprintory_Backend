package team.sprintory_backend.notification.services;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import team.sprintory_backend.notification.email.EmailSender;
import team.sprintory_backend.notification.email.RenderedEmail;
import team.sprintory_backend.notification.models.EmailOutboxMessage;

/**
 * Sends the queued notification emails every 15 seconds. Each email is sent with no transaction
 * open, so a slow mail server holds no database connection; one failing email never stops the
 * others. One backend instance, and a fixed delay: two runs never overlap.
 */
@Component
@RequiredArgsConstructor
public class EmailOutboxWorker {

    static final int BATCH_SIZE = 50;

    private final EmailOutboxService outbox;
    private final EmailSender sender;

    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    public void sendDue() {
        for (Long id : outbox.dueIds(BATCH_SIZE)) {
            EmailOutboxMessage message = outbox.find(id).orElse(null);
            if (message == null) {
                continue;
            }
            try {
                sender.send(message.getRecipientEmail(),
                        new RenderedEmail(message.getSubject(), message.getBodyText(), message.getBodyHtml()));
            } catch (RuntimeException e) {
                // EmailDeliveryException (server down, not configured) or a bad address.
                outbox.markFailed(id, e.getClass().getSimpleName() + ": " + e.getMessage());
                continue;
            }
            outbox.markSent(id);
        }
    }
}