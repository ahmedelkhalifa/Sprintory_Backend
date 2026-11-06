package team.sprintory_backend.comment.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.comment.models.TaskComment;

import java.util.Optional;

@Repository
public interface TaskCommentRepository extends JpaRepository<TaskComment, Long> {

    /**
     * One page of a task's thread, oldest first, deleted placeholders included. The authors are
     * fetched with it, so a page does not run one query per comment. Pass an unsorted pageable.
     */
    @EntityGraph(attributePaths = "author")
    Page<TaskComment> findByTaskIdOrderByCreatedAtAscIdAsc(Long taskId, Pageable pageable);

    /** A comment of this task only, so an id from another task gives the same 404 as a missing one. */
    Optional<TaskComment> findByIdAndTaskId(Long id, Long taskId);
}