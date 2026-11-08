package team.sprintory_backend.attachment.repositories;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.attachment.models.TaskAttachment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Deleted attachments are never returned: {@code TaskAttachment} carries {@code @SQLRestriction}. */
@Repository
public interface TaskAttachmentRepository extends JpaRepository<TaskAttachment, Long> {

    /** A task's live attachments, newest first, with their uploaders in the same query. */
    @EntityGraph(attributePaths = "uploader")
    List<TaskAttachment> findByTaskIdOrderByCreatedAtDescIdDesc(Long taskId);

    /**
     * A live attachment whose task is live too, with the task. A soft-deleted task hides its
     * attachments, so its files cannot be downloaded (docs/attachments-docs.md §1). The task
     * condition is written out rather than left to the task's {@code @SQLRestriction}.
     */
    @Query("select a from TaskAttachment a join fetch a.task t where a.id = :id and t.deletedAt is null")
    Optional<TaskAttachment> findLiveWithTaskById(@Param("id") Long id);

    // Nightly clean-up. Native SQL on purpose: these must see soft-deleted rows, which the
    // entity's @SQLRestriction hides from every JPA query.

    /** Removes the rows of attachments deleted before the cutoff; their files then have no row. */
    @Modifying
    @Transactional
    @Query(value = "delete from task_attachments where deleted_at < :cutoff", nativeQuery = true)
    int deleteDeletedBefore(@Param("cutoff") Instant cutoff);

    /** The key of every row, live or deleted: a stored file is kept while its row exists. */
    @Query(value = "select storage_key from task_attachments", nativeQuery = true)
    List<String> findAllStorageKeys();
}