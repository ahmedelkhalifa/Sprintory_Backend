package team.sprintory_backend.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {
    private final SecretKey key;
    private final long accessExpirationMs;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-expiration-ms}") long accessExpirationMs) {
        // HS256 requires a >= 256-bit key; a secret shorter than 32 bytes throws here at startup.
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpirationMs = accessExpirationMs;
    }

    public String generateAccessToken(String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(accessExpirationMs)))
                .signWith(key)
                .compact();
    }

    /** One-use-in-purpose, short-lived STOMP credential; never accepted as an HTTP access token. */
    public String generateChatSocketToken(String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(email)
                .claim("token_use", "chat_stomp")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(120)))
                .signWith(key)
                .compact();
    }

    public boolean isValidChatSocketToken(String token) {
        try {
            return "chat_stomp".equals(parse(token).get("token_use", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    // No refresh token here on purpose: refresh tokens are opaque rows, see RefreshTokenService.

    public String extractSubject(String token) {
        return parse(token).getSubject();
    }

    public boolean isValid(String token) {
        try {
            // STOMP credentials cannot authenticate HTTP, even if copied to a cookie.
            return !"chat_stomp".equals(parse(token).get("token_use", String.class));
        } catch (ExpiredJwtException e) {
            return false;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

}
