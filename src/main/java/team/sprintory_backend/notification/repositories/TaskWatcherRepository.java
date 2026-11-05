package team.sprintory_backend.notification.repositories;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import team.sprintory_backend.notification.models.TaskWatcher;
import team.sprintory_backend.notification.models.TaskWatcherId;

public interface TaskWatcherRepository extends JpaRepository<TaskWatcher, TaskWatcherId> {

    @Query("select w.id.userId from TaskWatcher w where w.id.taskId = :taskId")
    List<Long> findUserIdsByTaskId(@Param("taskId") Long taskId);

    /**
     * Starts watching; does nothing if the person already watches. One statement, so two
     * triggers at once (say, an assignment and a comment) cannot collide on the primary key.
     */
    @Modifying
    @Query(value = "insert into task_watchers (task_id, user_id, created_at) values (:taskId, :userId, now()) "
            + "on conflict do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("taskId") Long taskId, @Param("userId") Long userId);

    // The two deletes below are native on purpose: Task's @SQLRestriction would hide soft-deleted
    // tasks from a JPQL subquery, and their watcher rows must go too, or a restored task would
    // bring back a watcher who is no longer in the project.

    /** When someone leaves a project, or is removed from it. */
    @Modifying
    @Query(value = "delete from task_watchers w using tasks t "
            + "where w.task_id = t.id and t.project_id = :projectId and w.user_id = :userId", nativeQuery = true)
    int deleteInProject(@Param("projectId") Long projectId, @Param("userId") Long userId);

    /** The same for every project of a workspace, when someone leaves it or is removed from it. */
    @Modifying
    @Query(value = "delete from task_watchers w using tasks t, projects p "
            + "where w.task_id = t.id and t.project_id = p.id "
            + "and p.workspace_id = :workspaceId and w.user_id = :userId", nativeQuery = true)
    int deleteInWorkspace(@Param("workspaceId") Long workspaceId, @Param("userId") Long userId);
}