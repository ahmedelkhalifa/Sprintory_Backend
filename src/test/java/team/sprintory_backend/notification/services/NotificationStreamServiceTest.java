package team.sprintory_backend.notification.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import team.sprintory_backend.notification.dto.NotificationResponse;
import team.sprintory_backend.notification.events.NotificationCreated;
import team.sprintory_backend.notification.events.UnreadCountChanged;
import team.sprintory_backend.notification.models.NotificationType;
import team.sprintory_backend.notification.repositories.NotificationRepository;
import team.sprintory_backend.user.models.User;

/** The live stream, with emitters that record what was written instead of a real connection. */
class NotificationStreamServiceTest {

    private NotificationRepository repository;
    private List<Runnable> queuedWork;
    private NotificationStreamService service;
    private final List<RecordingEmitter> created = new ArrayList<>();
    private final User ada = User.builder().id(17L).firstName("Ada").lastName("Lovelace").build();
    private final User bob = User.builder().id(23L).firstName("Bob").lastName("Smith").build();

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        when(repository.countByRecipientIdAndReadAtIsNull(anyLong())).thenReturn(2L);
        queuedWork = new ArrayList<>();
        service = new NotificationStreamService(repository, queuedWork::add) {
            @Override
            SseEmitter newEmitter() {
                RecordingEmitter emitter = new RecordingEmitter();
                created.add(emitter);
                return emitter;
            }
        };
    }

    /** Subscribes and runs the worker job, as the real executor would right after. */
    private RecordingEmitter open(User user) {
        RecordingEmitter emitter = (RecordingEmitter) service.subscribe(user);
        runQueuedWork();
        return emitter;
    }

    private void runQueuedWork() {
        List<Runnable> work = new ArrayList<>(queuedWork);
        queuedWork.clear();
        work.forEach(Runnable::run);
    }

    private static NotificationResponse notification(Long id) {
        return new NotificationResponse(id, NotificationType.TASK_ASSIGNED, NotificationType.TASK_ASSIGNED.category(),
                Instant.parse("2026-11-03T14:05:31Z"), false, null, "Damir assigned you MOB-42", null,
                null, null, null, null);
    }

    // --- subscribe ---

    @Test
    void aNewStreamGetsTheRetryTimeThenTheCurrentCount() {
        RecordingEmitter tab = open(ada);

        assertEquals(2, tab.sent.size());
        assertTrue(tab.sent.get(0).startsWith("retry:3000\n"), tab.sent.get(0));
        assertTrue(tab.sent.get(1).contains("event:unread-count\n"), tab.sent.get(1));
        assertTrue(tab.sent.get(1).contains("count=2"), tab.sent.get(1));
        assertEquals(1, service.openStreams(17L));
    }

    @Test
    void theStreamRequestItselfNeverQueriesTheDatabase() {
        // Open-in-view would hold that connection for the whole life of the stream.
        service.subscribe(ada);
        verifyNoInteractions(repository);

        runQueuedWork();
        verify(repository).countByRecipientIdAndReadAtIsNull(17L);
    }

    @Test
    void aSixthStreamClosesTheOldest() {
        for (int i = 0; i < NotificationStreamService.MAX_STREAMS_PER_USER; i++) {
            open(ada);
        }
        RecordingEmitter sixth = open(ada);

        assertEquals(NotificationStreamService.MAX_STREAMS_PER_USER, service.openStreams(17L));
        assertTrue(created.get(0).completed, "the oldest is closed");
        assertFalse(sixth.completed);
    }

    // --- pushes after commit ---

    @Test
    void aNewNotificationReachesEveryTabOfTheRecipientAndNobodyElse() {
        RecordingEmitter adaTab1 = open(ada);
        RecordingEmitter adaTab2 = open(ada);
        RecordingEmitter bobTab = open(bob);
        when(repository.countByRecipientIdAndReadAtIsNull(17L)).thenReturn(3L);

        service.on(new NotificationCreated(17L, notification(981L)));

        for (RecordingEmitter tab : List.of(adaTab1, adaTab2)) {
            assertEquals(4, tab.sent.size());
            assertTrue(tab.sent.get(2).contains("event:notification\n"), tab.sent.get(2));
            assertTrue(tab.sent.get(2).contains("id=981"), tab.sent.get(2));
            assertTrue(tab.sent.get(3).contains("event:unread-count\n"));
            assertTrue(tab.sent.get(3).contains("count=3"));
        }
        assertEquals(2, bobTab.sent.size(), "Bob only has his own connect events");
    }

    @Test
    void someoneWithNoOpenStreamCostsNoQuery() {
        service.on(new NotificationCreated(99L, notification(981L)));
        service.on(new UnreadCountChanged(99L));

        verifyNoInteractions(repository);
    }

    @Test
    void readingPushesTheNewCountToEveryTab() {
        RecordingEmitter tab1 = open(ada);
        RecordingEmitter tab2 = open(ada);
        when(repository.countByRecipientIdAndReadAtIsNull(17L)).thenReturn(0L);

        service.on(new UnreadCountChanged(17L));

        assertTrue(tab1.sent.get(2).contains("count=0"));
        assertTrue(tab2.sent.get(2).contains("count=0"));
    }

    @Test
    void theCountIsReadOnceForAllTabs() {
        open(ada);
        open(ada);
        clearInvocations(repository);

        service.on(new UnreadCountChanged(17L));

        verify(repository).countByRecipientIdAndReadAtIsNull(17L);
    }

    // --- broken connections ---

    @Test
    void aBrokenTabIsDroppedWithoutAnErrorAndTheOthersStillReceive() {
        RecordingEmitter broken = open(ada);
        RecordingEmitter healthy = open(ada);
        broken.broken = true;

        service.on(new NotificationCreated(17L, notification(981L)));

        assertEquals(1, service.openStreams(17L));
        assertTrue(broken.completed);
        assertTrue(healthy.sent.get(2).contains("event:notification\n"));
    }

    @Test
    void whenTheLastTabBreaksThePersonIsForgotten() {
        RecordingEmitter only = open(ada);
        only.broken = true;

        service.keepAlive();

        assertEquals(0, service.openStreams(17L));
    }

    @Test
    void keepAliveSendsACommentToEveryStream() {
        RecordingEmitter adaTab = open(ada);
        RecordingEmitter bobTab = open(bob);

        service.keepAlive();

        assertEquals(":keep-alive\n\n", adaTab.sent.get(2));
        assertEquals(":keep-alive\n\n", bobTab.sent.get(2));
    }

    /** Records each event as the text that would go over the wire; objects appear as toString(). */
    static final class RecordingEmitter extends SseEmitter {
        final List<String> sent = new ArrayList<>();
        boolean broken;
        boolean completed;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (broken) {
                throw new IOException("Broken pipe");
            }
            StringBuilder text = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
                text.append(part.getData());
            }
            sent.add(text.toString());
        }

        @Override
        public void complete() {
            completed = true;
        }
    }
}