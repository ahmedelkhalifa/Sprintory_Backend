package team.sprintory_backend.attachment.storage;

import org.springframework.core.io.Resource;

import java.nio.file.Path;

/**
 * Where attachment bytes live (R25, PMP-33; docs/attachments-docs.md §6). Files are addressed by
 * a random key only, never by the user's file name. An upload is first written to a temporary
 * file, checked and converted there, and only then {@link #store stored}; a refused upload never
 * reaches the store.
 *
 * <p>{@link LocalAttachmentStorage} keeps them on disk (a Docker volume in production). A cloud
 * store could implement the same interface; the temporary files stay local either way.
 */
public interface AttachmentStorage {

    /** A new, empty temporary file for one upload. Delete it with {@link #deleteTemp} if it is not stored. */
    Path createTempFile();

    /** Moves the temporary file into the store under a new random key, and returns the key. */
    String store(Path tempFile);

    /**
     * The stored file, for streaming to a download.
     *
     * @throws IllegalArgumentException when the key is not a valid key
     * @throws StoredFileMissingException when no file has this key
     */
    Resource load(String key);

    /** Deletes the stored file; false when there was none. Never throws for a missing file. */
    boolean delete(String key);

    /** Deletes a temporary file, ignoring errors; null is ignored. */
    void deleteTemp(Path tempFile);
}