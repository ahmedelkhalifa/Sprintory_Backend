package team.sprintory_backend.sprint.repositories;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import team.sprintory_backend.sprint.models.Sprint;
import team.sprintory_backend.sprint.models.SprintStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SprintRepository extends JpaRepository<Sprint, Long> {
    /** Scalar lookup before project lock: do not load the Sprint entity until the lock is held. */
    @Query("select s.project.id from Sprint s where s.id = :sprintId")
    Optional<Long> findProjectId(@Param("sprintId") Long sprintId);
    List<Sprint> findByProjectIdAndStatusInOrderByNumberAsc(Long projectId, Collection<SprintStatus> statuses);
    List<Sprint> findByProjectIdOrderByNumberAsc(Long projectId);
    /** Newest first; sprints without points (Jira import) are skipped. Velocity and capacity suggestion (PMP-42). */
    List<Sprint> findByProjectIdAndStatusAndCompletedPointsNotNullOrderByCompletedAtDescIdDesc(
            Long projectId, SprintStatus status, Pageable pageable);
    Optional<Sprint> findFirstByProjectIdAndStatus(Long projectId, SprintStatus status);
    /** Active sprints past their end date, for the daily reminder (PMP-41). */
    @Query("select s.id from Sprint s where s.status = team.sprintory_backend.sprint.models.SprintStatus.ACTIVE "
            + "and s.endDate < :today order by s.id")
    List<Long> findOverdueActiveIds(@Param("today") java.time.LocalDate today);
    /** Every active sprint, for the nightly burndown snapshot (PMP-42). */
    @Query("select s.id from Sprint s where s.status = team.sprintory_backend.sprint.models.SprintStatus.ACTIVE "
            + "order by s.id")
    List<Long> findActiveIds();
    @Query("select coalesce(max(s.number), 0) from Sprint s where s.project.id = :projectId")
    int maxNumber(@Param("projectId") Long projectId);
}
