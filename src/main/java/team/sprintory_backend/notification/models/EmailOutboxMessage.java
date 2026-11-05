package team.sprintory_backend.notification.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A notification email, rendered and queued in the action's transaction and sent later by the
 * outbox worker, with retries (docs/notifications-docs.md §10). Self-contained: no foreign keys.
 * {@code lastError} holds an exception message only, never the body.
 */
@Entity
@Table(name = "email_outbox")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailOutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_email", nullable = false, updatable = false)
    private String recipientEmail;

    @Column(nullable = false, updatable = false)
    private String subject;

    @Column(name = "body_text", nullable = false, updatable = false, columnDefinition = "text")
    private String bodyText;

    @Column(name = "body_html", nullable = false, updatable = false, columnDefinition = "text")
    private String bodyHtml;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EmailOutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    /** When the worker should try next; null once SENT or FAILED. */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    public void markSent(Instant now) {
        status = EmailOutboxStatus.SENT;
        sentAt = now;
        nextAttemptAt = null;
    }

    /**
     * Counts a failed attempt. The n-th failure waits {@code retryDelays[n - 1]}; once every delay
     * is used up (one first try plus one per delay) the email is given up as FAILED.
     *
     * @return true when this failure was the last one allowed
     */
    public boolean recordFailure(String error, Instant now, List<Duration> retryDelays) {
        attempts++;
        lastError = (error == null || error.length() <= 1000) ? error : error.substring(0, 1000);
        if (attempts > retryDelays.size()) {
            status = EmailOutboxStatus.FAILED;
            nextAttemptAt = null;
            return true;
        }
        nextAttemptAt = now.plus(retryDelays.get(attempts - 1));
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EmailOutboxMessage other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}