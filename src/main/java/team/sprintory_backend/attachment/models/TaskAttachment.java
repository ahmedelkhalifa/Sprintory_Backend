package team.sprintory_backend.attachment.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.SQLRestriction;
import team.sprintory_backend.audit.BaseEntity;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.user.models.User;

import java.time.Instant;

/**
 * A file attached to a task (R25, docs/attachments-docs.md). The bytes are on disk under
 * {@link #storageKey}; {@link #fileName} is display text only and never a path.
 *
 * <p>Deleting is soft, and unlike comments a deleted attachment is not shown at all, so the
 * {@link SQLRestriction} hides it from every JPA read. Native SQL (the nightly clean-up) must
 * filter {@code deleted_at} itself. The restriction does not cover the task: a soft-deleted
 * task's attachments are still found here, so always load the task through
 * {@code TaskRepository} before giving one out.
 */
@Entity
@Table(name = "task_attachments")
@SQLRestriction("deleted_at is null")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TaskAttachment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Task task;

    /** Null once the account no longer exists. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploader_id", updatable = false)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User uploader;

    @Column(name = "file_name", nullable = false, updatable = false, length = 255)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false, updatable = false, length = 10)
    private AttachmentType fileType;

    /** The stored size, after image compression. */
    @Column(name = "size_bytes", nullable = false, updatable = false)
    private Long sizeBytes;

    /** The size as uploaded. */
    @Column(name = "original_size_bytes", nullable = false, updatable = false)
    private Long originalSizeBytes;

    /** A random UUID, the file's only name on disk. */
    @Column(name = "storage_key", nullable = false, updatable = false, unique = true, length = 36)
    private String storageKey;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Who deleted it, the uploader or the Project Manager; no foreign key. */
    @Column(name = "deleted_by_id")
    private Long deletedById;

    /** Uploaded by the given user; false once the uploader's account is gone. */
    public boolean isUploadedBy(User user) {
        return uploader != null && uploader.getId().equals(user.getId());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TaskAttachment other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}