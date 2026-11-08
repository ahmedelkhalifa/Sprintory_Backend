package team.sprintory_backend.sprint.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import team.sprintory_backend.sprint.models.SprintAvailability;
import java.util.List;
import java.util.Optional;

public interface SprintAvailabilityRepository extends JpaRepository<SprintAvailability, Long> {
    List<SprintAvailability> findBySprintId(Long sprintId);
    Optional<SprintAvailability> findBySprintIdAndUserId(Long sprintId, Long userId);
}
