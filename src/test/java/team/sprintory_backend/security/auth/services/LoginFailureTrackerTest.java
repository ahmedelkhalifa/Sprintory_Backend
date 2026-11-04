package team.sprintory_backend.security.auth.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import team.sprintory_backend.audit.models.AuditAction;
import team.sprintory_backend.audit.models.AuditChange;
import team.sprintory_backend.audit.services.AuditLogService;
import team.sprintory_backend.notification.events.RepeatedLoginFailures;
import team.sprintory_backend.user.models.Provider;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;

class LoginFailureTrackerTest {

    private static final String EMAIL = "bob@example.com";

    private UserRepository userRepository;
    private AuditLogService auditLog;
    private ApplicationEventPublisher events;
    private MutableClock clock;
    private LoginFailureTracker tracker;
    private User bob;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        auditLog = mock(AuditLogService.class);
        events = mock(ApplicationEventPublisher.class);
        clock = new MutableClock(Instant.parse("2026-11-02T10:00:00Z"));
        tracker = new LoginFailureTracker(userRepository, auditLog, events, clock);
        bob = User.builder().id(23L).firstName("Bob").lastName("Smith").email(EMAIL)
                .provider(Provider.LOCAL).enabled(true).build();
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(bob));
    }

    @Test
    void fourFailuresWriteNothingAndTheFifthWritesOneEntry() {
        fail(4);
        verifyNoInteractions(auditLog, events);

        fail(1);

        verify(auditLog).recordInAllWorkspacesOf(same(bob), isNull(), eq(AuditAction.LOGIN_FAILED_REPEATED),
                eq(AuditChange.of("failedAttempts", null, "5")));
        // The account owner is told at the same moment (R27).
        verify(events).publishEvent(new RepeatedLoginFailures(bob, 5));
    }

    @Test
    void failuresSpreadOverMoreThanTheWindowDoNotAddUp() {
        fail(4);
        clock.advance(LoginFailureTracker.WINDOW.plusSeconds(1));

        fail(1);

        verifyNoInteractions(auditLog);
    }

    @Test
    void onlyTheFailuresInsideTheWindowCount() {
        fail(3);
        clock.advance(Duration.ofMinutes(10));
        fail(1);
        clock.advance(Duration.ofMinutes(6)); // the first three are now older than 15 minutes
        fail(1);
        verifyNoInteractions(auditLog);

        fail(3); // 2 + 3 inside the window

        verify(auditLog, times(1)).recordInAllWorkspacesOf(any(), any(), any(), any(AuditChange[].class));
    }

    @Test
    void theTenthFailureWritesASecondEntry() {
        fail(9);
        verify(auditLog, times(1)).recordInAllWorkspacesOf(any(), any(), any(), any(AuditChange[].class));

        fail(1);

        verify(auditLog, times(2)).recordInAllWorkspacesOf(any(), any(), any(), any(AuditChange[].class));
    }

    @Test
    void aSuccessfulLoginResetsTheCount() {
        fail(4);
        tracker.reset(EMAIL);

        fail(4);

        verifyNoInteractions(auditLog);
    }

    @Test
    void unknownGoogleAndUnverifiedAccountsAreIgnored() {
        User google = User.builder().id(30L).email("g@example.com").provider(Provider.GOOGLE).enabled(true).build();
        User unverified = User.builder().id(31L).email("u@example.com").provider(Provider.LOCAL).enabled(false).build();
        when(userRepository.findByEmail("g@example.com")).thenReturn(Optional.of(google));
        when(userRepository.findByEmail("u@example.com")).thenReturn(Optional.of(unverified));

        for (int i = 0; i < 5; i++) {
            tracker.recordFailure("nobody@example.com");
            tracker.recordFailure("g@example.com");
            tracker.recordFailure("u@example.com");
        }

        verifyNoInteractions(auditLog, events);
        assertEquals(0, tracker.trackedAccounts(), "only real accounts are kept in memory");
    }

    @Test
    void pruningForgetsAccountsWithNoRecentFailure() {
        fail(2);
        assertEquals(1, tracker.trackedAccounts());

        clock.advance(Duration.ofMinutes(5));
        tracker.pruneStale();
        assertEquals(1, tracker.trackedAccounts(), "still inside the window");

        clock.advance(LoginFailureTracker.WINDOW);
        tracker.pruneStale();
        assertEquals(0, tracker.trackedAccounts());
    }

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            tracker.recordFailure(EMAIL);
            clock.advance(Duration.ofSeconds(1));
        }
    }

    /** A clock the test moves forward by hand. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}