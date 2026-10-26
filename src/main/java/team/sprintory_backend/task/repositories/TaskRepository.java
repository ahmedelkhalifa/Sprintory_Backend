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
    @Query("select max(t.boardPosition) from Task t where t.project.id = :projectId and t.status.id = :statusId")
    Long findMaxBoardPosition(@Param("projectId") Long projectId, @Param("statusId") Long statusId);

    /** The highest backlog rank in the project, or null if it has no tasks. */
    @Query("select max(t.backlogRank) from Task t where t.project.id = :projectId")
    Long findMaxBacklogRank(@Param("projectId") Long projectId);

    /**
     * The task right after {@code position} in a column, ignoring {@code taskId} (the one being
     * moved). Pass {@code PageRequest.of(0, 1)}; an empty list means "nothing after it".
     */
    @Query("select t from Task t where t.project.id = :projectId and t.status.id = :statusId "
            + "and t.id <> :taskId and t.boardPosition > :position order by t.boardPosition, t.id")
    List<Task> findNextInColumn(@Param("projectId") Long projectId, @Param("statusId") Long statusId,
                                @Param("taskId") Long taskId, @Param("position") long position,
                                Pageable limit);

    /** The same for the backlog. */
    @Query("select t from Task t where t.project.id = :projectId "
            + "and t.id <> :taskId and t.backlogRank > :rank order by t.backlogRank, t.id")
    List<Task> findNextInBacklog(@Param("projectId") Long projectId, @Param("taskId") Long taskId,
                                 @Param("rank") long rank, Pageable limit);

    /** A whole column in board order; used only to renumber it when a move finds no room. */
    @Query("select t from Task t where t.project.id = :projectId and t.status.id = :statusId "
            + "order by t.boardPosition, t.id")
    List<Task> findColumnInBoardOrder(@Param("projectId") Long projectId, @Param("statusId") Long statusId);

    /** The whole backlog in order; used only to renumber it when a move finds no room. */
    @Query("select t from Task t where t.project.id = :projectId order by t.backlogRank, t.id")
    List<Task> findProjectInBacklogOrder(@Param("projectId") Long projectId);
}