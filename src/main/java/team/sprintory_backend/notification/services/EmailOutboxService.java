package team.sprintory_backend.notification.services;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.notification.email.RenderedEmail;
import team.sprintory_backend.notification.models.EmailOutboxMessage;
import team.sprintory_backend.notification.models.EmailOutboxStatus;
import team.sprintory_backend.notification.repositories.EmailOutboxRepository;

/**
 * The persisted queue behind notification emails (docs/notifications-docs.md §10). Emails are
 * queued in the action's transaction and sent later by {@link EmailOutboxWorker}, so a slow or
 * broken mail server never slows down or fails the action. The worker calls the methods below
 * from outside, so each runs in its own short transaction.
 */
@Service
@RequiredArgsConstructor
public class EmailOutboxService {

    private static final Logger log = LoggerFactory.getLogger(EmailOutboxService.class);

    /** The wait after the 1st, 2nd, ... failure; after one more failure the email is given up. */
    static final List<Duration> RETRY_DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(6));

    private final EmailOutboxRepository repository;

    // Not final: @Value on a final field with @RequiredArgsConstructor fails at startup.
    @Value("${app.notifications.retention-days}")
    private int retentionDays;

    /**
     * Queues an already rendered email, due at once. Not transactional itself on purpose: it joins
     * the caller's transaction through the repository, and a failure before the save (none is
     * expected) must not mark the action's transaction for rollback.
     */
    public void enqueue(String recipientEmail, RenderedEmail email) {
        Instant now = Instant.now();
        repository.save(EmailOutboxMessage.builder()
                .recipientEmail(recipientEmail)
                .subject(email.subject())
                .bodyText(email.plainText())
                .bodyHtml(email.html())
                .status(EmailOutboxStatus.PENDING)
                .attempts(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .build());
    }

    @Transactional(readOnly = true)
    public List<Long> dueIds(int limit) {
        return repository.findDueIds(EmailOutboxStatus.PENDING, Instant.now(), PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public Optional<EmailOutboxMessage> find(Long id) {
        return repository.findById(id).filter(message -> message.getStatus() == EmailOutboxStatus.PENDING);
    }

    @Transactional
    public void markSent(Long id) {
        repository.findById(id).ifPresent(message -> message.markSent(Instant.now()));
    }

    /** Finished emails are kept as long as notifications, then deleted once a night. PENDING ones stay. */
    @Scheduled(cron = "0 55 3 * * *")
    @Transactional
    public void deleteFinished() {
        int deleted = repository.deleteFinishedBefore(List.of(EmailOutboxStatus.SENT, EmailOutboxStatus.FAILED),
                Instant.now().minus(Duration.ofDays(retentionDays)));
        if (deleted > 0) {
            log.info("Deleted {} sent or failed notification emails older than {} days", deleted, retentionDays);
        }
    }

    /** Schedules the next try, or gives the email up after the last one. Never logs the body. */
    @Transactional
    public void markFailed(Long id, String error) {
        repository.findById(id).ifPresent(message -> {
            boolean gaveUp = message.recordFailure(error, Instant.now(), RETRY_DELAYS);
            if (gaveUp) {
                log.warn("Gave up on notification email {} after {} attempts: {}", id, message.getAttempts(), error);
            } else {
                log.info("Notification email {} failed (attempt {}), retrying at {}: {}",
                        id, message.getAttempts(), message.getNextAttemptAt(), error);
            }
        });
    }
}