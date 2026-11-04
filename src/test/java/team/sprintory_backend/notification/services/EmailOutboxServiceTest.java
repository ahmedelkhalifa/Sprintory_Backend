package team.sprintory_backend.notification.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import team.sprintory_backend.notification.email.RenderedEmail;
import team.sprintory_backend.notification.models.EmailOutboxMessage;
import team.sprintory_backend.notification.models.EmailOutboxStatus;
import team.sprintory_backend.notification.repositories.EmailOutboxRepository;

class EmailOutboxServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    private EmailOutboxRepository repository;
    private EmailOutboxService service;

    @BeforeEach
    void setUp() {
        repository = mock(EmailOutboxRepository.class);
        service = new EmailOutboxService(repository);
    }

    private static EmailOutboxMessage pending() {
        return EmailOutboxMessage.builder().id(7L).recipientEmail("ada@example.com").subject("s")
                .bodyText("t").bodyHtml("h").status(EmailOutboxStatus.PENDING).attempts(0)
                .nextAttemptAt(NOW).createdAt(NOW).build();
    }

    // --- queueing ---

    @Test
    void enqueueStoresTheRenderedEmailAsPendingAndDueAtOnce() {
        Instant before = Instant.now();

        service.enqueue("ada@example.com", new RenderedEmail("Subject", "Plain", "<p>Html</p>"));

        ArgumentCaptor<EmailOutboxMessage> saved = ArgumentCaptor.forClass(EmailOutboxMessage.class);
        verify(repository).save(saved.capture());
        EmailOutboxMessage m = saved.getValue();
        assertEquals("ada@example.com", m.getRecipientEmail());
        assertEquals("Subject", m.getSubject());
        assertEquals("Plain", m.getBodyText());
        assertEquals("<p>Html</p>", m.getBodyHtml());
        assertEquals(EmailOutboxStatus.PENDING, m.getStatus());
        assertEquals(0, m.getAttempts());
        assertFalse(m.getNextAttemptAt().isBefore(before));
        assertNull(m.getSentAt());
    }

    // --- the backoff schedule (on the entity) ---

    @Test
    void eachFailureWaitsLongerThenTheSixthGivesUp() {
        EmailOutboxMessage m = pending();
        Duration[] expected = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
                Duration.ofHours(1), Duration.ofHours(6)};

        for (int i = 0; i < expected.length; i++) {
            assertFalse(m.recordFailure("SMTP down", NOW, EmailOutboxService.RETRY_DELAYS));
            assertEquals(i + 1, m.getAttempts());
            assertEquals(EmailOutboxStatus.PENDING, m.getStatus());
            assertEquals(NOW.plus(expected[i]), m.getNextAttemptAt());
        }

        assertTrue(m.recordFailure("SMTP down", NOW, EmailOutboxService.RETRY_DELAYS), "the 6th failure gives up");
        assertEquals(6, m.getAttempts());
        assertEquals(EmailOutboxStatus.FAILED, m.getStatus());
        assertNull(m.getNextAttemptAt());
        assertEquals("SMTP down", m.getLastError());
    }

    @Test
    void aVeryLongErrorIsCutToTheColumn() {
        EmailOutboxMessage m = pending();

        m.recordFailure("e".repeat(5000), NOW, EmailOutboxService.RETRY_DELAYS);

        assertEquals(1000, m.getLastError().length());
    }

    // --- worker-facing methods ---

    @Test
    void markSentClosesTheEmail() {
        EmailOutboxMessage m = pending();
        when(repository.findById(7L)).thenReturn(Optional.of(m));

        service.markSent(7L);

        assertEquals(EmailOutboxStatus.SENT, m.getStatus());
        assertNotNull(m.getSentAt());
        assertNull(m.getNextAttemptAt());
    }

    @Test
    void markFailedSchedulesTheRetryAMinuteLater() {
        EmailOutboxMessage m = pending();
        when(repository.findById(7L)).thenReturn(Optional.of(m));
        Instant before = Instant.now();

        service.markFailed(7L, "EmailDeliveryException: Unable to deliver email");

        assertEquals(1, m.getAttempts());
        assertEquals(EmailOutboxStatus.PENDING, m.getStatus());
        assertFalse(m.getNextAttemptAt().isBefore(before.plus(Duration.ofMinutes(1))));
        assertEquals("EmailDeliveryException: Unable to deliver email", m.getLastError());
    }

    @Test
    void findSkipsEmailsThatAreNoLongerPending() {
        EmailOutboxMessage sent = pending();
        sent.markSent(NOW);
        when(repository.findById(7L)).thenReturn(Optional.of(sent));

        assertTrue(service.find(7L).isEmpty());
    }

    @Test
    void theNightlyCleanupDeletesOnlyFinishedEmailsOlderThanTheRetention() {
        ReflectionTestUtils.setField(service, "retentionDays", 90);
        Instant before = Instant.now();

        service.deleteFinished();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteFinishedBefore(eq(List.of(EmailOutboxStatus.SENT, EmailOutboxStatus.FAILED)),
                cutoff.capture());
        Instant ninetyDaysAgo = before.minus(Duration.ofDays(90));
        assertFalse(cutoff.getValue().isBefore(ninetyDaysAgo));
        assertTrue(cutoff.getValue().isBefore(ninetyDaysAgo.plusSeconds(60)));
    }

    @Test
    void dueIdsAsksForPendingRowsThatAreDue() {
        service.dueIds(50);

        verify(repository).findDueIds(eq(EmailOutboxStatus.PENDING), any(Instant.class), eq(PageRequest.of(0, 50)));
    }
}