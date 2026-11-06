package team.sprintory_backend.project.charter.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import team.sprintory_backend.project.charter.models.CharterGoal;
import java.util.List;
import java.util.Optional;

public interface CharterGoalRepository extends JpaRepository<CharterGoal, Long> {
    List<CharterGoal> findByProjectIdOrderBySortOrderAscIdAsc(Long projectId);
    Optional<CharterGoal> findByIdAndProjectId(Long id, Long projectId);
}
