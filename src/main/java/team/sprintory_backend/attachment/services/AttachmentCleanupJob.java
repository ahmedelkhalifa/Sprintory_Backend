package team.sprintory_backend.attachment.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import team.sprintory_backend.attachment.repositories.TaskAttachmentRepository;
import team.sprintory_backend.attachment.storage.AttachmentStorage;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Nightly clean-up of attachment files (docs/attachments-docs.md §6), at 04:10, after the other
 * nightly jobs:
 * <ol>
 *   <li>rows of attachments soft-deleted more than {@code app.attachments.retention-days} ago
 *       are removed (the task history keeps the file name);</li>
 *   <li>stored files that no row points at are deleted: those rows, and rows removed by a
 *       permanent workspace delete's cascade;</li>
 *   <li>temporary files of crashed uploads are deleted.</li>
 * </ol>
 * Files younger than {@link #GRACE} are never deleted in steps 2 and 3: an upload stores its
 * file a moment before its row commits.
 */
@Component
public class AttachmentCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AttachmentCleanupJob.class);

    static final Duration GRACE = Duration.ofDays(1);

    private final TaskAttachmentRepository attachmentRepository;
    private final AttachmentStorage storage;
    private final int retentionDays;

    public AttachmentCleanupJob(TaskAttachmentRepository attachmentRepository, AttachmentStorage storage,
                                @Value("${app.attachments.retention-days:30}") int retentionDays) {
        this.attachmentRepository = attachmentRepository;
        this.storage = storage;
        this.retentionDays = retentionDays;
    }

    /** What one run removed. */
    public record Result(int rows, int files, int tempFiles) {
    }

    @Scheduled(cron = "0 10 4 * * *")
    public Result run() {
        Instant now = Instant.now();
        int rows = attachmentRepository.deleteDeletedBefore(now.minus(Duration.ofDays(retentionDays)));

        // Read the keys after removing rows, so their files count as unreferenced in this run.
        Set<String> referenced = new HashSet<>(attachmentRepository.findAllStorageKeys());
        Instant cutoff = now.minus(GRACE);
        int files = 0;
        try (Stream<AttachmentStorage.StoredFile> stored = storage.storedFiles()) {
            for (AttachmentStorage.StoredFile file : (Iterable<AttachmentStorage.StoredFile>) stored::iterator) {
                if (!referenced.contains(file.key()) && file.lastModified().isBefore(cutoff) && storage.delete(file.key())) {
                    files++;
                }
            }
        }
        int tempFiles = storage.deleteTempFilesOlderThan(cutoff);

        if (rows + files + tempFiles > 0) {
            log.info("Attachment clean-up: removed {} deleted attachments older than {} days, {} unreferenced files, "
                    + "{} temporary files", rows, retentionDays, files, tempFiles);
        }
        return new Result(rows, files, tempFiles);
    }
}