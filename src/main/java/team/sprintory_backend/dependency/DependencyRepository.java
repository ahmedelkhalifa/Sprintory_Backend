package team.sprintory_backend.dependency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface DependencyRepository extends JpaRepository<TaskDependency, Long> {
    List<TaskDependency> findByProjectIdOrderByIdAsc(Long projectId);
    boolean existsByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    // Native projections explicitly exclude soft-deleted endpoints; no entity association can
    // silently discard a retained edge from cycle validation.
    @Query(value = """
        select d.id, b.id, b.task_key, b.title, bc.name, bc.category,
               t.id, t.task_key, t.title, tc.name, tc.category
        from task_dependency d join tasks b on b.id=d.blocker_task_id
        join tasks t on t.id=d.blocked_task_id
        join project_board_columns bc on bc.id=b.status_column_id
        join project_board_columns tc on tc.id=t.status_column_id
        where d.project_id=:projectId and b.deleted_at is null and t.deleted_at is null
          and (b.id=:taskId or t.id=:taskId)
        order by d.id
        """, nativeQuery = true)
    List<Object[]> liveLinks(@Param("projectId") Long projectId, @Param("taskId") Long taskId);

    @Query(value = """
        select d.blocked_task_id, b.id, b.task_key, b.title, c.name, c.category
        from task_dependency d join tasks b on b.id=d.blocker_task_id
        join tasks t on t.id=d.blocked_task_id
        join project_board_columns c on c.id=b.status_column_id
        where d.blocked_task_id in (:taskIds) and b.deleted_at is null and t.deleted_at is null
          and c.category <> 'DONE' order by b.task_key,b.id
        """, nativeQuery = true)
    List<Object[]> openBlockers(@Param("taskIds") Collection<Long> taskIds);

    @Query(value = """
        select distinct d.blocked_task_id from task_dependency d
        join tasks b on b.id=d.blocker_task_id join tasks t on t.id=d.blocked_task_id
        join project_board_columns c on c.id=b.status_column_id
        where d.project_id=:projectId and b.deleted_at is null and t.deleted_at is null
          and c.category <> 'DONE'
        """, nativeQuery = true)
    List<Long> blockedIds(@Param("projectId") Long projectId);

    @Query(value = "select id,task_key from tasks where project_id=:projectId and id in (:ids)", nativeQuery = true)
    List<Object[]> keysIncludingDeleted(@Param("projectId") Long projectId, @Param("ids") Collection<Long> ids);
}
