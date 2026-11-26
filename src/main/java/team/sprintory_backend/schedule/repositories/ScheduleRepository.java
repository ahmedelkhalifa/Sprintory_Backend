package team.sprintory_backend.schedule.repositories;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import team.sprintory_backend.schedule.dto.ScheduleTask;
import team.sprintory_backend.schedule.dto.ScheduleDependency;
import team.sprintory_backend.task.models.Task;

import java.util.List;

public interface ScheduleRepository extends Repository<Task, Long> {
    // Scalar projection, one query for all live top-level tasks; no labels/descriptions or N+1.
    @Query("""
        select new team.sprintory_backend.schedule.dto.ScheduleTask(
            t.id, t.key, t.title, t.startDate, t.dueDate, c.category, c.id, c.name,
            t.type, a.id, case when a.id is null then null else concat(a.firstName, ' ', a.lastName) end, s.id)
        from Task t join t.status c left join t.assignee a left join t.sprint s
        where t.project.id = :projectId and t.parent is null
        order by t.startDate asc nulls last, t.id asc
        """)
    List<ScheduleTask> findRows(@Param("projectId") Long projectId);

    // Retained links to deleted tasks are discarded by the service's live row index.
    @Query("""
        select new team.sprintory_backend.schedule.dto.ScheduleDependency(d.id, d.blockerId, d.blockedId)
        from TaskDependency d where d.projectId = :projectId order by d.id
        """)
    List<ScheduleDependency> findLinks(@Param("projectId") Long projectId);
}
