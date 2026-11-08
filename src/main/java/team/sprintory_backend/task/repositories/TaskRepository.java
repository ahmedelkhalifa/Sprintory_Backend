package team.sprintory_backend.task.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.task.models.Task;

import java.util.Collection;
import team.sprintory_backend.project.models.BoardColumnCategory;
import java.util.List;
import java.util.Optional;

/** Deleted tasks are never returned: {@code Task} carries {@code @SQLRestriction}. */
@Repository
public interface TaskRepository extends JpaRepository<Task, Long>, JpaSpecificationExecutor<Task> {

    /**
     * Looks the task up inside the project, so a task id from another project gives 404 instead
     * of leaking across projects. Fetches the status column for the response.
     */
    @EntityGraph(attributePaths = {"status", "assignee"})
    Optional<Task> findByIdAndProjectId(Long id, Long projectId);

    /** The filtered task list; the status column and assignee come in the same query. */
    @Override
    @EntityGraph(attributePaths = {"status", "assignee"})
    Page<Task> findAll(Specification<Task> spec, Pageable pageable);

    /** Sprint order; the parent owns the assignment, and subtasks follow it. */
    List<Task> findBySprintIdAndParentIsNullOrderBySprintPositionAscIdAsc(Long sprintId);

    /** Only live top-level tasks count for board WIP. */
    long countByProjectIdAndStatusIdAndParentIsNull(Long projectId, Long statusId);

    long countByParentId(Long parentId);

    /** Active child tasks with this status, for column deletion UI (not WIP). */
    long countByProjectIdAndStatusIdAndParentIsNotNull(Long projectId, Long statusId);

    long countByParentIdAndStatusCategoryNot(Long parentId, BoardColumnCategory category);

    /**
     * Open subtasks of the given parents outside one column: a column delete moves that column's
     * subtasks along with their parents, so only the others stay open.
     */
    long countByParentIdInAndStatusCategoryNotAndStatusIdNot(Collection<Long> parentIds,
                                                             BoardColumnCategory category, Long statusId);

    @EntityGraph(attributePaths = {"status", "assignee"})
    List<Task> findByParentIdOrderByIdAsc(Long parentId);

    /** One query per task page, not a pair of subtask-count queries per task. */
    @Query(value = "select t.parent_task_id, count(*), "
            + "sum(case when c.category = 'DONE' then 1 else 0 end) "
            + "from tasks t join project_board_columns c on c.id = t.status_column_id "
            + "where t.deleted_at is null and t.parent_task_id in (:parentIds) "
            + "group by t.parent_task_id", nativeQuery = true)
    List<Object[]> findSubtaskProgress(@Param("parentIds") Collection<Long> parentIds);

    /** Deleted tasks cannot be fetched by JPA because Task has @SQLRestriction. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update tasks t set deleted_at = null, deleted_with_parent_id = null,
                updated_at = now(), updated_by = :actor,
                status_column_id = case when exists (
                    select 1 from project_board_columns c
                    where c.id = t.status_column_id and c.deleted_at is null
                ) then t.status_column_id else :fallbackId end
            where t.project_id = :projectId and t.id = :taskId and t.deleted_at is not null
              and (t.parent_task_id is null or exists (
                  select 1 from tasks p where p.id = t.parent_task_id
                  and p.project_id = t.project_id and p.deleted_at is null
              ))
            """, nativeQuery = true)
    int restoreOne(@Param("projectId") Long projectId, @Param("taskId") Long taskId,
                   @Param("fallbackId") Long fallbackId, @Param("actor") String actor);

    /** Only children deleted as a direct consequence of this parent's deletion. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update tasks t set deleted_at = null, deleted_with_parent_id = null,
                updated_at = now(), updated_by = :actor,
                status_column_id = case when exists (
                    select 1 from project_board_columns c
                    where c.id = t.status_column_id and c.deleted_at is null
                ) then t.status_column_id else :fallbackId end
            where t.project_id = :projectId and t.parent_task_id = :parentId
              and t.deleted_with_parent_id = :parentId and t.deleted_at is not null
            """, nativeQuery = true)
    int restoreCascadedChildren(@Param("projectId") Long projectId,
                                 @Param("parentId") Long parentId,
                                 @Param("fallbackId") Long fallbackId, @Param("actor") String actor);

    // --- assignees who leave ---

    /**
     * Unassigns the user from the project's tasks, when they are removed from the project: only
     * project members may be assignees. A bulk update, so it does not touch the audit columns.
     */
    @Modifying
    @Query("update Task t set t.assignee = null where t.project.id = :projectId and t.assignee.id = :userId")
    int unassignInProject(@Param("projectId") Long projectId, @Param("userId") Long userId);

    /** The same for every project of a workspace, when the user leaves or is removed from it. */
    @Modifying
    @Query("update Task t set t.assignee = null where t.assignee.id = :userId "
            + "and t.project.id in (select p.id from Project p where p.workspace.id = :workspaceId)")
    int unassignInWorkspace(@Param("workspaceId") Long workspaceId, @Param("userId") Long userId);

    // --- order on the board and in the backlog (see TaskRanks) ---

    /** The highest position in a column, or null if the column is empty. */
    @Query("select max(t.boardPosition) from Task t where t.project.id = :projectId and t.status.id = :statusId and t.parent is null")
    Long findMaxBoardPosition(@Param("projectId") Long projectId, @Param("statusId") Long statusId);

    /** The highest backlog rank in the project, or null if it has no tasks. */
    @Query("select max(t.backlogRank) from Task t where t.project.id = :projectId and t.parent is null")
    Long findMaxBacklogRank(@Param("projectId") Long projectId);

    /**
     * The task right after {@code position} in a column, ignoring {@code taskId} (the one being
     * moved). Pass {@code PageRequest.of(0, 1)}; an empty list means "nothing after it".
     */
    @Query("select t from Task t where t.project.id = :projectId and t.status.id = :statusId "
            + "and t.parent is null and t.id <> :taskId and t.boardPosition > :position order by t.boardPosition, t.id")
    List<Task> findNextInColumn(@Param("projectId") Long projectId, @Param("statusId") Long statusId,
                                @Param("taskId") Long taskId, @Param("position") long position,
                                Pageable limit);

    /** The same for the backlog. */
    @Query("select t from Task t where t.project.id = :projectId "
            + "and t.parent is null and t.id <> :taskId and t.backlogRank > :rank order by t.backlogRank, t.id")
    List<Task> findNextInBacklog(@Param("projectId") Long projectId, @Param("taskId") Long taskId,
                                 @Param("rank") long rank, Pageable limit);

    /** A whole column in board order; used only to renumber it when a move finds no room. */
    @Query("select t from Task t where t.project.id = :projectId and t.status.id = :statusId "
            + "and t.parent is null order by t.boardPosition, t.id")
    List<Task> findColumnInBoardOrder(@Param("projectId") Long projectId, @Param("statusId") Long statusId);

    /** Rehome both parent tasks and subtasks before soft-deleting a board column. */
    @Query("select t from Task t where t.project.id = :projectId and t.status.id = :statusId "
            + "order by t.boardPosition, t.id")
    List<Task> findAllInColumnForDeletion(@Param("projectId") Long projectId,
                                           @Param("statusId") Long statusId);

    /** The whole backlog in order; used only to renumber it when a move finds no room. */
    @Query("select t from Task t where t.project.id = :projectId and t.parent is null order by t.backlogRank, t.id")
    List<Task> findProjectInBacklogOrder(@Param("projectId") Long projectId);
}