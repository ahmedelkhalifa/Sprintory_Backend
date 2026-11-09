package team.sprintory_backend.sprint.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import team.sprintory_backend.sprint.models.Sprint;
import team.sprintory_backend.sprint.models.SprintStatus;
import java.util.List;
import java.util.Optional;

public interface SprintRepository extends JpaRepository<Sprint, Long> {
    /** Scalar lookup before project lock: do not load the Sprint entity until the lock is held. */
    @Query("select s.project.id from Sprint s where s.id = :sprintId")
    Optional<Long> findProjectId(@Param("sprintId") Long sprintId);
    List<Sprint> findByProjectIdAndStatusNotOrderByNumberAsc(Long projectId, SprintStatus status);
    List<Sprint> findByProjectIdOrderByNumberAsc(Long projectId);
    List<Sprint> findTop3ByProjectIdAndStatusOrderByCompletedAtDescIdDesc(Long projectId, SprintStatus status);
    Optional<Sprint> findFirstByProjectIdAndStatus(Long projectId, SprintStatus status);
    @Query("select coalesce(max(s.number), 0) from Sprint s where s.project.id = :projectId")
    int maxNumber(@Param("projectId") Long projectId);
}
