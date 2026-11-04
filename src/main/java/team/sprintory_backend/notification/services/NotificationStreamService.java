package team.sprintory_backend.notification.services;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import team.sprintory_backend.notification.dto.UnreadCountResponse;
import team.sprintory_backend.notification.events.NotificationCreated;
import team.sprintory_backend.notification.events.UnreadCountChanged;
import team.sprintory_backend.notification.repositories.NotificationRepository;
import team.sprintory_backend.user.models.User;

/**
 * The live bell (docs/notifications-docs.md §4): one Server-Sent Events stream per open tab,
 * kept in memory (one backend instance). Events go out only after the transaction that caused
 * them commits.
 *
 * <p>The stream request itself never touches the database. Open-in-view keeps that request's
 * EntityManager, and with it any connection it borrows, for the whole life of the stream (up to
 * {@link #TIMEOUT_MS}); a query there would pin one pooled connection per open bell. The first
 * count is therefore read on a worker thread.
 *
 * <p>A send that fails (closed tab, dead network) only drops that stream: the push runs on the
 * request of whoever acted, and their response must never depend on someone else's connection.
 */
@Service
public class NotificationStreamService {

    private static final Logger log = LoggerFactory.getLogger(NotificationStreamService.class);

    static final long TIMEOUT_MS = 10 * 60 * 1000L;
    static final long RETRY_MS = 3000L;
    static final int MAX_STREAMS_PER_USER = 5;
    static final String UNREAD_COUNT = "unread-count";
    static final String NOTIFICATION = "notification";

    private final NotificationRepository repository;
    private final Executor worker;
    /** userId → that person's open streams, oldest first. Changed only inside compute(). */
    private final Map<Long, List<SseEmitter>> streams = new ConcurrentHashMap<>();

    @Autowired
    public NotificationStreamService(NotificationRepository repository,
                                     @Qualifier("applicationTaskExecutor") Executor worker) {
        this.repository = repository;
        this.worker = worker;
    }

    /**
     * Opens a stream for the caller. A sixth stream closes the oldest one. The browser is told to
     * reconnect after {@link #RETRY_MS}, then gets the current unread count.
     */
    public SseEmitter subscribe(User user) {
        Long userId = user.getId();
        SseEmitter emitter = newEmitter();
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> {
            remove(userId, emitter);
            emitter.complete();
        });
        emitter.onError(error -> remove(userId, emitter));

        SseEmitter[] evicted = new SseEmitter[1];
        streams.compute(userId, (id, mine) -> {
            List<SseEmitter> list = (mine == null) ? new CopyOnWriteArrayList<>() : mine;
            list.add(emitter);
            if (list.size() > MAX_STREAMS_PER_USER) {
                evicted[0] = list.remove(0);
            }
            return list;
        });
        if (evicted[0] != null) {
            closeQuietly(evicted[0]);
        }

        send(userId, emitter, SseEmitter.event().reconnectTime(RETRY_MS).comment("connected"));
        worker.execute(() -> send(userId, emitter, countEvent(userId)));
        return emitter;
    }

    /** A new notification: push it, then the new count, to every open tab of the recipient. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(NotificationCreated event) {
        List<SseEmitter> mine = streams.get(event.recipientId());
        if (mine == null || mine.isEmpty()) {
            return;
        }
        SseEmitter.SseEventBuilder count = countEvent(event.recipientId());
        for (SseEmitter emitter : mine) {
            send(event.recipientId(), emitter, SseEmitter.event().name(NOTIFICATION).data(event.notification()));
            send(event.recipientId(), emitter, count);
        }
    }

    /** Something was read: every open tab of that person gets the new count. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(UnreadCountChanged event) {
        List<SseEmitter> mine = streams.get(event.userId());
        if (mine == null || mine.isEmpty()) {
            return;
        }
        SseEmitter.SseEventBuilder count = countEvent(event.userId());
        for (SseEmitter emitter : mine) {
            send(event.userId(), emitter, count);
        }
    }

    /** Proxies close a connection that stays silent for about a minute; a comment keeps it open. */
    @Scheduled(fixedRate = 25_000)
    public void keepAlive() {
        streams.forEach((userId, mine) -> {
            for (SseEmitter emitter : mine) {
                send(userId, emitter, SseEmitter.event().comment("keep-alive"));
            }
        });
    }

    /** How many streams this person has open; for tests. */
    int openStreams(Long userId) {
        List<SseEmitter> mine = streams.get(userId);
        return (mine == null) ? 0 : mine.size();
    }

    /** A real emitter; tests replace it with one that records what was sent. */
    SseEmitter newEmitter() {
        return new SseEmitter(TIMEOUT_MS);
    }

    private SseEmitter.SseEventBuilder countEvent(Long userId) {
        return SseEmitter.event().name(UNREAD_COUNT)
                .data(new UnreadCountResponse(repository.countByRecipientIdAndReadAtIsNull(userId)));
    }

    private void send(Long userId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (Exception e) {
            // IOException for a closed connection, IllegalStateException for a completed emitter.
            log.debug("Dropping a notification stream of user {}: {}", userId, e.toString());
            remove(userId, emitter);
            closeQuietly(emitter);
        }
    }

    private void remove(Long userId, SseEmitter emitter) {
        streams.computeIfPresent(userId, (id, mine) -> {
            mine.remove(emitter);
            return mine.isEmpty() ? null : mine;
        });
    }

    private static void closeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // already completed or broken; nothing left to close
        }
    }
}