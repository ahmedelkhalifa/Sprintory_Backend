package team.sprintory_backend.retro.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.retro.models.RetroItem;

import java.util.List;
import java.util.Optional;

@Repository
public interface RetroItemRepository extends JpaRepository<RetroItem, Long> {

    /** Only the retro's id, so the retro can be locked before the card itself is read. */
    @Query("select i.retro.id from RetroItem i where i.id = :itemId")
    Optional<Long> findRetroIdById(@Param("itemId") Long itemId);

    /** The board's live cards, oldest first. */
    List<RetroItem> findByRetroIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(Long retroId);
}
