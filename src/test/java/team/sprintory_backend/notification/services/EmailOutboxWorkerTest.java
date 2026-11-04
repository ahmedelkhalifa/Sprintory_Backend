package team.sprintory_backend.notification.services;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import team.sprintory_backend.notification.email.EmailDeliveryException;
import team.sprintory_backend.notification.email.EmailSender;
import team.sprintory_backend.notification.email.RenderedEmail;
import team.sprintory_backend.notification.models.EmailOutboxMessage;
import team.sprintory_backend.notification.models.EmailOutboxStatus;

/** The worker: send each due email, record what happened, never let one failure stop the rest. */
class EmailOutboxWorkerTest {

    private EmailOutboxService outbox;
    private EmailSender sender;
    private EmailOutboxWorker worker;

    @BeforeEach
    void setUp() {
        outbox = mock(EmailOutboxService.class);
        sender = mock(EmailSender.class);
        worker = new EmailOutboxWorker(outbox, sender);
    }

    private void givenDue(Long... ids) {
        when(outbox.dueIds(EmailOutboxWorker.BATCH_SIZE)).thenReturn(List.of(ids));
        for (Long id : ids) {
            when(outbox.find(id)).thenReturn(Optional.of(EmailOutboxMessage.builder().id(id)
                    .recipientEmail("user" + id + "@example.com").subject("Subject " + id)
                    .bodyText("Plain " + id).bodyHtml("<p>" + id + "</p>")
                    .status(EmailOutboxStatus.PENDING).attempts(0).nextAttemptAt(Instant.now())
                    .createdAt(Instant.now()).build()));
        }
    }

    @Test
    void aDeliveredEmailIsMarkedSent() {
        givenDue(1L);

        worker.sendDue();

        verify(sender).send("user1@example.com", new RenderedEmail("Subject 1", "Plain 1", "<p>1</p>"));
        verify(outbox).markSent(1L);
        verify(outbox, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void aMailServerFailureIsRecordedForARetryAndTheNextEmailIsStillSent() {
        givenDue(1L, 2L);
        doThrow(new EmailDeliveryException("Unable to deliver email"))
                .when(sender).send(eq("user1@example.com"), any());

        worker.sendDue();

        verify(outbox).markFailed(1L, "EmailDeliveryException: Unable to deliver email");
        verify(outbox, never()).markSent(1L);
        verify(outbox).markSent(2L);
    }

    @Test
    void anEmailThatIsNoLongerPendingIsSkipped() {
        when(outbox.dueIds(EmailOutboxWorker.BATCH_SIZE)).thenReturn(List.of(1L));
        when(outbox.find(1L)).thenReturn(Optional.empty());

        worker.sendDue();

        verifyNoInteractions(sender);
        verify(outbox, never()).markSent(anyLong());
    }

    @Test
    void nothingDueSendsNothing() {
        when(outbox.dueIds(EmailOutboxWorker.BATCH_SIZE)).thenReturn(List.of());

        worker.sendDue();

        verifyNoInteractions(sender);
    }
}