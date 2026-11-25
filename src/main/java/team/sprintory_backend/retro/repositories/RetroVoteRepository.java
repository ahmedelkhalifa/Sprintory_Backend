package team.sprintory_backend.retro.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.retro.models.RetroVote;

import java.util.List;

@Repository
public interface RetroVoteRepository extends JpaRepository<RetroVote, Long> {

    /** How many votes the person has used in the retro (at most 3). */
    long countByRetroIdAndUserId(Long retroId, Long userId);

    /** Every vote of the board; at most 3 per member, so this stays small. */
    List<RetroVote> findByRetroId(Long retroId);

    List<RetroVote> findByItemId(Long itemId);

    /** A deleted card gives its votes back. */
    @Modifying
    @Query("delete from RetroVote v where v.item.id = :itemId")
    void deleteByItemId(@Param("itemId") Long itemId);
}
