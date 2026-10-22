package team.sprintory_backend.security.auth.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import team.sprintory_backend.security.auth.models.OneTimeToken;
import team.sprintory_backend.security.auth.repositories.OneTimeTokenRepository;
import team.sprintory_backend.security.exceptions.InvalidTokenException;
import team.sprintory_backend.user.models.User;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static team.sprintory_backend.security.auth.models.OneTimeTokenPurpose.RESET_PASSWORD;
import static team.sprintory_backend.security.auth.models.OneTimeTokenPurpose.VERIFY_EMAIL;

/** Pure service tests with a mocked repository; the real queries are not exercised here. */
class OneTimeTokenServiceTest {

    private OneTimeTokenRepository repository;
    private OneTimeTokenService service;
    private final User user = User.builder().id(7L).email("ada@example.com").build();

    @BeforeEach
    void setUp() {
        repository = mock(OneTimeTokenRepository.class);
        service = new OneTimeTokenService(repository, Duration.ofHours(24).toMillis());
    }

    @Test
    void issueReplacesOlderLinksAndStoresOnlyTheHash() {
        String raw = service.issue(user, RESET_PASSWORD);

        InOrder order = inOrder(repository);
        order.verify(repository).deleteByUserAndPurpose(user, RESET_PASSWORD);
        ArgumentCaptor<OneTimeToken> saved = ArgumentCaptor.forClass(OneTimeToken.class);
        order.verify(repository).save(saved.capture());

        OneTimeToken token = saved.getValue();
        assertEquals(OpaqueTokens.hash(raw), token.getTokenHash());
        assertNotEquals(raw, token.getTokenHash());
        assertEquals(RESET_PASSWORD, token.getPurpose());
        assertSame(user, token.getUser());
        Duration validFor = Duration.between(Instant.now(), token.getExpiresAt());
        assertTrue(validFor.compareTo(Duration.ofHours(23)) > 0 && validFor.compareTo(Duration.ofHours(24)) <= 0);
    }

    @Test
    void consumeReturnsTheOwnerAndDeletesTheRow() {
        OneTimeToken stored = stored(Instant.now().plusSeconds(3600));
        when(repository.findByTokenHashAndPurpose(OpaqueTokens.hash("raw"), VERIFY_EMAIL))
                .thenReturn(Optional.of(stored));

        assertSame(user, service.consume("raw", VERIFY_EMAIL));
        verify(repository).delete(stored);
    }

    @Test
    void consumeRejectsAnUnknownTokenOrOneIssuedForTheOtherPurpose() {
        // The lookup includes the purpose, so a reset token used as a verification token finds nothing.
        when(repository.findByTokenHashAndPurpose(OpaqueTokens.hash("raw"), VERIFY_EMAIL))
                .thenReturn(Optional.empty());

        assertThrows(InvalidTokenException.class, () -> service.consume("raw", VERIFY_EMAIL));
        verify(repository, never()).delete(any());
    }

    @Test
    void consumeRejectsAnExpiredTokenWithoutDeletingIt() {
        when(repository.findByTokenHashAndPurpose(OpaqueTokens.hash("raw"), VERIFY_EMAIL))
                .thenReturn(Optional.of(stored(Instant.now().minusSeconds(1))));

        assertThrows(InvalidTokenException.class, () -> service.consume("raw", VERIFY_EMAIL));
        verify(repository, never()).delete(any());
    }

    @Test
    void consumeRejectsABlankTokenWithoutTouchingTheDatabase() {
        assertThrows(InvalidTokenException.class, () -> service.consume(null, VERIFY_EMAIL));
        assertThrows(InvalidTokenException.class, () -> service.consume("  ", VERIFY_EMAIL));
        verifyNoInteractions(repository);
    }

    private OneTimeToken stored(Instant expiresAt) {
        return OneTimeToken.builder().id(1L).tokenHash(OpaqueTokens.hash("raw")).user(user)
                .purpose(VERIFY_EMAIL).expiresAt(expiresAt).build();
    }
}