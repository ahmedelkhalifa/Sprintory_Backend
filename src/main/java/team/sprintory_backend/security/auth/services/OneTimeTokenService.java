package team.sprintory_backend.security.auth.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.security.auth.models.OneTimeToken;
import team.sprintory_backend.security.auth.models.OneTimeTokenPurpose;
import team.sprintory_backend.security.auth.repositories.OneTimeTokenRepository;
import team.sprintory_backend.security.exceptions.InvalidTokenException;
import team.sprintory_backend.user.models.User;

import java.time.Instant;

/**
 * Issues and consumes the tokens behind emailed links (email verification, password reset).
 * Same storage model as refresh tokens: opaque random value in the link, SHA-256 hash in the
 * database, and the row is deleted on use so each link works exactly once.
 */
@Service
public class OneTimeTokenService {

    private static final Logger log = LoggerFactory.getLogger(OneTimeTokenService.class);

    private final OneTimeTokenRepository oneTimeTokenRepository;
    private final long linkTtlMs;

    public OneTimeTokenService(OneTimeTokenRepository oneTimeTokenRepository,
                               @Value("${app.auth.link-expiration-ms:86400000}") long linkTtlMs) {
        this.oneTimeTokenRepository = oneTimeTokenRepository;
        this.linkTtlMs = linkTtlMs;
    }

    /**
     * Issues a token and returns the raw value for the emailed link. Any earlier token of the
     * same purpose for this user stops working, so only the newest email is valid.
     */
    @Transactional
    public String issue(User user, OneTimeTokenPurpose purpose) {
        oneTimeTokenRepository.deleteByUserAndPurpose(user, purpose);

        String rawToken = OpaqueTokens.generate();
        oneTimeTokenRepository.save(OneTimeToken.builder()
                .tokenHash(OpaqueTokens.hash(rawToken))
                .user(user)
                .purpose(purpose)
                .expiresAt(Instant.now().plusMillis(linkTtlMs))
                .build());
        return rawToken;
    }

    /**
     * Verifies a raw token for the given purpose and deletes it.
     *
     * @throws InvalidTokenException if the token is missing, unknown, expired, already used,
     *                               or was issued for the other purpose
     */
    @Transactional
    public User consume(String rawToken, OneTimeTokenPurpose purpose) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidTokenException("Link token is missing.");
        }
        OneTimeToken stored = oneTimeTokenRepository
                .findByTokenHashAndPurpose(OpaqueTokens.hash(rawToken), purpose)
                .orElseThrow(() -> new InvalidTokenException("Link is not valid."));

        if (stored.isExpired()) {
            // Not deleted here on purpose: throwing rolls the transaction back, so the delete
            // would be undone anyway. The nightly cleanup removes expired rows.
            throw new InvalidTokenException("Link has expired.");
        }

        oneTimeTokenRepository.delete(stored);
        return stored.getUser();
    }

    /** Expired rows are harmless but accumulate, so clear them out once a night. */
    @Scheduled(cron = "0 20 3 * * *")
    @Transactional
    public int deleteExpired() {
        int removed = oneTimeTokenRepository.deleteExpired(Instant.now());
        if (removed > 0) {
            log.info("Removed {} expired one-time tokens", removed);
        }
        return removed;
    }
}