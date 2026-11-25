package team.sprintory_backend.retro.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.retro.models.RetroAction;

import java.util.List;

@Repository
public interface RetroActionRepository extends JpaRepository<RetroAction, Long> {

    /** A retro's actions, oldest first. */
    List<RetroAction> findByRetroIdOrderByCreatedAtAscIdAsc(Long retroId);
}
