package team.sprintory_backend.attachment.repositories;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.attachment.models.TaskAttachment;

import java.util.List;

/** Deleted attachments are never returned: {@code TaskAttachment} carries {@code @SQLRestriction}. */
@Repository
public interface TaskAttachmentRepository extends JpaRepository<TaskAttachment, Long> {

    /** A task's live attachments, newest first, with their uploaders in the same query. */
    @EntityGraph(attributePaths = "uploader")
    List<TaskAttachment> findByTaskIdOrderByCreatedAtDescIdDesc(Long taskId);
}