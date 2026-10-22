package team.sprintory_backend.security.auth.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.security.auth.models.RefreshToken;
import team.sprintory_backend.security.auth.repositories.RefreshTokenRepository;
import team.sprintory_backend.security.exceptions.InvalidTokenException;
import team.sprintory_backend.user.models.User;

import java.time.Instant;

/**
 * Issues and verifies refresh tokens. Tokens are opaque random strings; the database keeps
 * only their SHA-256 hash, and a row existing is what makes a token valid. That is what lets
 * logout revoke tokens that have not expired yet.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final long refreshTtlMs;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                               @Value("${app.jwt.refresh-expiration-ms}") long refreshTtlMs) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTtlMs = refreshTtlMs;
    }

    /**
     * Issues a token for the user and returns the raw value, which belongs in the refresh cookie.
     * The raw value is never stored and cannot be recovered afterwards.
     *
     * @param rememberMe whether the cookie should outlive the browser session; kept on the row
     *                   so a rotated token keeps the same kind
     */
    @Transactional
    public String issue(User user, boolean rememberMe) {
        String rawToken = OpaqueTokens.generate();

        refreshTokenRepository.save(RefreshToken.builder()
                .tokenHash(OpaqueTokens.hash(rawToken))
                .user(user)
                .expiresAt(Instant.now().plusMillis(refreshTtlMs))
                .rememberMe(rememberMe)
                .build());
        return rawToken;
    }

    /**
     * Verifies a raw token and consumes it, so each token works exactly once. The caller issues
     * a replacement with {@link #issue(User, boolean)}, passing on the returned row's
     * {@code rememberMe}. The row's user is already loaded.
     *
     * @throws InvalidTokenException if the token is missing, unknown, expired or already used
     */
    @Transactional
    public RefreshToken verifyAndConsume(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidTokenException("Refresh token is missing.");
        }
        RefreshToken stored = refreshTokenRepository.findByTokenHash(OpaqueTokens.hash(rawToken))
                .orElseThrow(() -> new InvalidTokenException("Refresh token is not valid."));

        if (stored.isExpired()) {
            // Not deleted here on purpose: throwing rolls the transaction back, so the delete
            // would be undone anyway. The nightly cleanup removes expired rows.
            throw new InvalidTokenException("Refresh token has expired.");
        }

        refreshTokenRepository.delete(stored);
        return stored;
    }

    /** Every refresh token this user holds, on every device, stops working. */
    @Transactional
    public void revokeAll(User user) {
        refreshTokenRepository.deleteByUser(user);
    }

    /**
     * Logout: finds the owner of the presented token and revokes all their tokens. An unknown
     * or already-revoked token is ignored, so logout always succeeds and clears the cookies.
     */
    @Transactional
    public void revokeAllForToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHash(OpaqueTokens.hash(rawToken))
                .ifPresent(stored -> revokeAll(stored.getUser()));
    }

    /** Expired rows are harmless but accumulate, so clear them out once a night. */
    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public int deleteExpired() {
        int removed = refreshTokenRepository.deleteExpired(Instant.now());
        if (removed > 0) {
            log.info("Removed {} expired refresh tokens", removed);
        }
        return removed;
    }
}