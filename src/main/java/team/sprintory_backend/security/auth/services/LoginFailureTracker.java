package team.sprintory_backend.security.auth.services;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.audit.models.AuditAction;
import team.sprintory_backend.audit.models.AuditChange;
import team.sprintory_backend.audit.services.AuditLogService;
import team.sprintory_backend.notification.events.RepeatedLoginFailures;
import team.sprintory_backend.user.models.Provider;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;

/**
 * Detects repeated failed logins for the audit log (R05, docs/audit-log-docs.md §4.5): every
 * {@value #THRESHOLD} wrong passwords within {@link #WINDOW} for one existing, verified
 * email-and-password account write one {@code LOGIN_FAILED_REPEATED} entry into each workspace
 * of that person. A successful login resets the count.
 *
 * <p>The counts are kept in memory: one backend instance runs on one VPS, and a restart only
 * resets them. Unknown emails are never stored, so the map is bounded by the number of accounts.
 */
@Component
public class LoginFailureTracker {

    static final int THRESHOLD = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final UserRepository userRepository;
    private final AuditLogService auditLog;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    @Autowired
    public LoginFailureTracker(UserRepository userRepository, AuditLogService auditLog,
                               ApplicationEventPublisher events) {
        this(userRepository, auditLog, events, Clock.systemUTC());
    }

    LoginFailureTracker(UserRepository userRepository, AuditLogService auditLog,
                        ApplicationEventPublisher events, Clock clock) {
        this.userRepository = userRepository;
        this.auditLog = auditLog;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Counts one wrong password for {@code email} (already normalized). Its own transaction: the
     * login that failed has none, and the entry must stay even though the login throws.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String email) {
        User user = userRepository.findByEmail(email)
                .filter(found -> found.getProvider() == Provider.LOCAL && found.isEnabled())
                .orElse(null);
        if (user == null) {
            return;
        }
        Instant now = clock.instant();
        boolean burst = countAndCheck(email, now);
        if (burst) {
            auditLog.recordInAllWorkspacesOf(user, null, AuditAction.LOGIN_FAILED_REPEATED,
                    AuditChange.of("failedAttempts", null, String.valueOf(THRESHOLD)));
            // The account owner is told too, always (docs/notifications-docs.md §5).
            events.publishEvent(new RepeatedLoginFailures(user, THRESHOLD));
        }
    }

    /** A successful login starts the count again. */
    public void reset(String email) {
        failures.remove(email);
    }

    /** Forgets accounts whose last failure is older than the window. */
    @Scheduled(fixedRate = 15 * 60 * 1000)
    public void pruneStale() {
        Instant cutoff = clock.instant().minus(WINDOW);
        failures.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                return entry.getValue().isEmpty() || !entry.getValue().peekLast().isAfter(cutoff);
            }
        });
    }

    /** How many accounts are being counted; for tests. */
    int trackedAccounts() {
        return failures.size();
    }

    /**
     * Adds the failure, drops the ones outside the window, and tells whether this one completes
     * a burst. A burst empties the count, so the next entry needs another full burst.
     */
    private boolean countAndCheck(String email, Instant now) {
        Deque<Instant> times = failures.computeIfAbsent(email, key -> new ArrayDeque<>());
        synchronized (times) {
            Instant cutoff = now.minus(WINDOW);
            while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) {
                times.pollFirst();
            }
            times.addLast(now);
            if (times.size() >= THRESHOLD) {
                times.clear();
                return true;
            }
            return false;
        }
    }
}