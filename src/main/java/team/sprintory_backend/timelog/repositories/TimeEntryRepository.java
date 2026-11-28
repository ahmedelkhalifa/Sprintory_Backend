package team.sprintory_backend.timelog.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import team.sprintory_backend.timelog.models.TimeEntry;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TimeEntryRepository extends JpaRepository<TimeEntry, Long> {
    List<TimeEntry> findByTaskIdAndUserIdOrderByWorkDateDescIdDesc(Long taskId, Long userId);

    @Query("select e.taskId from TimeEntry e where e.id = :id")
    Optional<Long> findTaskId(@Param("id") Long id);

    // Includes entries on soft-deleted tasks: deletion/restoration cannot bypass the daily cap.
    @Query("""
        select coalesce(sum(e.minutes), 0) from TimeEntry e
        where e.workspaceId = :workspaceId and e.userId = :userId and e.workDate = :date
          and e.id <> :excludedId
        """)
    long dailyMinutes(@Param("workspaceId") Long workspaceId, @Param("userId") Long userId,
                      @Param("date") LocalDate date, @Param("excludedId") Long excludedId);

    // One aggregate query; native SQL must explicitly exclude both kinds of tombstones.
    @Query(value = """
        select coalesce(sum(case when t.id = :taskId then e.minutes else 0 end), 0),
               coalesce(sum(case when t.parent_task_id = :taskId then e.minutes else 0 end), 0)
        from time_entry e join tasks t on t.id = e.task_id
        where e.deleted_at is null and t.deleted_at is null
          and (t.id = :taskId or t.parent_task_id = :taskId)
        """, nativeQuery = true)
    List<Object[]> totals(@Param("taskId") Long taskId);
}
