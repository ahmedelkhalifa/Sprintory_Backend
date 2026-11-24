package team.sprintory_backend.retro.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.retro.models.RetroAction;

@Repository
public interface RetroActionRepository extends JpaRepository<RetroAction, Long> {
}
