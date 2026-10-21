package team.sprintory_backend.security.auth.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.security.auth.models.RefreshToken;
import team.sprintory_backend.user.models.User;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** Fetches the owner in the same query so the caller can read it after the transaction ends. */
    @Query("select rt from RefreshToken rt join fetch rt.user where rt.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHash(@Param("tokenHash") String tokenHash);

    void deleteByUser(User user);

    @Modifying
    @Query("delete from RefreshToken rt where rt.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}