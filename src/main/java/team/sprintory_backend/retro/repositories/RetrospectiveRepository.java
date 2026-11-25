package team.sprintory_backend.retro.repositories;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.retro.models.Retrospective;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RetrospectiveRepository extends JpaRepository<Retrospective, Long> {

    Optional<Retrospective> findBySprintId(Long sprintId);

    /** The retro id of one sprint, for {@code SprintResponse.retroId}. */
    @Query("select r.id from Retrospective r where r.sprint.id = :sprintId")
    Optional<Long> findIdBySprintId(@Param("sprintId") Long sprintId);

    /** The retros of several sprints in one query, for the sprint history. */
    List<Retrospective> findBySprintIdIn(Collection<Long> sprintIds);

    /**
     * Loads the retro with a row lock. Votes take it so two votes by the same person cannot both
     * pass the 3-per-retro check, and closing takes it so no change lands after the close.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Retrospective r where r.id = :retroId")
    Optional<Retrospective> lockById(@Param("retroId") Long retroId);
}
