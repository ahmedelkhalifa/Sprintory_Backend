package team.sprintory_backend.retro.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.retro.models.RetroVote;

@Repository
public interface RetroVoteRepository extends JpaRepository<RetroVote, Long> {
}
