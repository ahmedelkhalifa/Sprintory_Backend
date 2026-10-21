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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Issues and verifies refresh tokens. Tokens are opaque random strings; the database keeps
 * only their SHA-256 hash, and a row existing is what makes a token valid. That is what lets
 * logout revoke tokens that have not expired yet.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
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
     */
    @Transactional
    public String issue(User user) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        refreshTokenRepository.save(RefreshToken.builder()
                .tokenHash(hash(rawToken))
                .user(user)
                .expiresAt(Instant.now().plusMillis(refreshTtlMs))
                .build());
        return rawToken;
    }

    /**
     * Verifies a raw token and consumes it, so each token works exactly once. The caller issues
     * a replacement with {@link #issue(User)}.
     *
     * @throws InvalidTokenException if the token is missing, unknown, expired or already used
     */
    @Transactional
    public User verifyAndConsume(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidTokenException("Refresh token is missing.");
        }
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new InvalidTokenException("Refresh token is not valid."));

        if (stored.isExpired()) {
            // Not deleted here on purpose: throwing rolls the transaction back, so the delete
            // would be undone anyway. The nightly cleanup removes expired rows.
            throw new InvalidTokenException("Refresh token has expired.");
        }

        refreshTokenRepository.delete(stored);
        return stored.getUser();
    }

    /** Logout: every refresh token this user holds, on every device, stops working. */
    @Transactional
    public void revokeAll(User user) {
        refreshTokenRepository.deleteByUser(user);
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

    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}