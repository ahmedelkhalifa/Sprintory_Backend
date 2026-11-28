package team.sprintory_backend.sprint.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import team.sprintory_backend.sprint.models.SprintDailySnapshot;

import java.time.LocalDate;
import java.util.List;

public interface SprintDailySnapshotRepository extends JpaRepository<SprintDailySnapshot, Long> {

    List<SprintDailySnapshot> findBySprintIdOrderBySnapshotDateAsc(Long sprintId);

    /** Writes the day's row, or replaces it: running twice for the same day is harmless. */
    @Modifying
    @Query(value = """
            insert into sprint_daily_snapshots (sprint_id, snapshot_date, total_points, remaining_points,
                total_tasks, remaining_tasks, unestimated_tasks, recorded_at)
            values (:sprintId, :date, :totalPoints, :remainingPoints, :totalTasks, :remainingTasks,
                :unestimatedTasks, now())
            on conflict (sprint_id, snapshot_date) do update set
                total_points = excluded.total_points, remaining_points = excluded.remaining_points,
                total_tasks = excluded.total_tasks, remaining_tasks = excluded.remaining_tasks,
                unestimated_tasks = excluded.unestimated_tasks, recorded_at = excluded.recorded_at""",
            nativeQuery = true)
    void upsert(@Param("sprintId") Long sprintId, @Param("date") LocalDate date,
                @Param("totalPoints") int totalPoints, @Param("remainingPoints") int remainingPoints,
                @Param("totalTasks") int totalTasks, @Param("remainingTasks") int remainingTasks,
                @Param("unestimatedTasks") int unestimatedTasks);
}
