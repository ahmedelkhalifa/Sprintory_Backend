package team.sprintory_backend.sprint.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import team.sprintory_backend.sprint.models.SprintScopeChange;

public interface SprintScopeChangeRepository extends JpaRepository<SprintScopeChange, Long> {
    Page<SprintScopeChange> findBySprintIdOrderByChangedAtDescIdDesc(Long sprintId, Pageable pageable);
}
