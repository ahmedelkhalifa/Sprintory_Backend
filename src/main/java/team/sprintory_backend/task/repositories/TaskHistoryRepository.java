package team.sprintory_backend.task.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.task.models.TaskHistoryEntry;

/** The task activity feed. Append-only: callers only save and read, never update or delete. */
@Repository
public interface TaskHistoryRepository extends JpaRepository<TaskHistoryEntry, Long> {

    /** Append a directional dependency snapshot without loading a soft-deleted task entity. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = """
        insert into task_history(task_id,type,old_value,new_value,ref_id,actor_id,actor_name,occurred_at)
        values (:taskId,:type,:oldValue,:newValue,:refId,:actorId,:actorName,:occurredAt)
        """, nativeQuery = true)
    int appendDependency(@org.springframework.data.repository.query.Param("taskId") Long taskId,
        @org.springframework.data.repository.query.Param("type") String type,
        @org.springframework.data.repository.query.Param("oldValue") String oldValue,
        @org.springframework.data.repository.query.Param("newValue") String newValue,
        @org.springframework.data.repository.query.Param("refId") Long refId,
        @org.springframework.data.repository.query.Param("actorId") Long actorId,
        @org.springframework.data.repository.query.Param("actorName") String actorName,
        @org.springframework.data.repository.query.Param("occurredAt") java.time.Instant occurredAt);

    /** One page of a task's feed, in the pageable's sort (always a {@code TaskHistoryOrder}'s). */
    Page<TaskHistoryEntry> findByTaskId(Long taskId, Pageable pageable);
}