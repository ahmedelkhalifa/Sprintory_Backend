package team.sprintory_backend.security.auth.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.security.auth.models.OneTimeToken;
import team.sprintory_backend.security.auth.models.OneTimeTokenPurpose;
import team.sprintory_backend.user.models.User;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface OneTimeTokenRepository extends JpaRepository<OneTimeToken, Long> {

    /** Fetches the owner in the same query so the caller can read it after the transaction ends. */
    @Query("select t from OneTimeToken t join fetch t.user "
            + "where t.tokenHash = :tokenHash and t.purpose = :purpose")
    Optional<OneTimeToken> findByTokenHashAndPurpose(@Param("tokenHash") String tokenHash,
                                                     @Param("purpose") OneTimeTokenPurpose purpose);

    void deleteByUserAndPurpose(User user, OneTimeTokenPurpose purpose);

    @Modifying
    @Query("delete from OneTimeToken t where t.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}