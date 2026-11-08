package team.sprintory_backend.sprint.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import team.sprintory_backend.sprint.models.SprintCommitment;
import java.util.List;

public interface SprintCommitmentRepository extends JpaRepository<SprintCommitment, Long> {
    List<SprintCommitment> findBySprintIdOrderByPositionAsc(Long sprintId);
}
