package team.sprintory_backend.task.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.task.models.TaskHistoryEntry;

/** The task activity feed. Append-only: callers only save and read, never update or delete. */
@Repository
public interface TaskHistoryRepository extends JpaRepository<TaskHistoryEntry, Long> {

    /** One page of a task's feed, in the pageable's sort (always a {@code TaskHistoryOrder}'s). */
    Page<TaskHistoryEntry> findByTaskId(Long taskId, Pageable pageable);
}