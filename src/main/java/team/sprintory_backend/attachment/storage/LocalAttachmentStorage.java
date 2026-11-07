package team.sprintory_backend.attachment.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Attachments on the local disk under {@code app.attachments.dir} (env {@code ATTACHMENTS_DIR}).
 *
 * <pre>
 * &lt;dir&gt;/.tmp/upload-*.tmp           uploads being checked
 * &lt;dir&gt;/3f/a2/3fa2...-....-....     stored files, named by key only
 * </pre>
 *
 * The two sub-folder levels (the key's first four characters) keep any one folder small. A key
 * must be a lower-case UUID before it is turned into a path, and the resolved path must stay
 * inside the root, so a bad key in the database can never point elsewhere.
 */
@Component
public class LocalAttachmentStorage implements AttachmentStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalAttachmentStorage.class);

    private static final Pattern KEY = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    static final String TEMP_DIR = ".tmp";

    private final Path root;
    private final Path tempDir;

    /** Creates the folders at startup, so a missing or read-only volume fails fast. */
    public LocalAttachmentStorage(@Value("${app.attachments.dir:./data/attachments}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        this.tempDir = root.resolve(TEMP_DIR);
        try {
            Files.createDirectories(tempDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the attachment folder " + root, e);
        }
        log.info("Attachments are stored in {}", root);
    }

    @Override
    public Path createTempFile() {
        try {
            return Files.createTempFile(tempDir, "upload-", ".tmp");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create a temporary upload file", e);
        }
    }

    @Override
    public String store(Path tempFile) {
        String key = UUID.randomUUID().toString();
        Path target = pathOf(key);
        try {
            Files.createDirectories(target.getParent());
            try {
                Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store the uploaded file", e);
        }
        return key;
    }

    @Override
    public Resource load(String key) {
        Path file = pathOf(key);
        if (!Files.isRegularFile(file)) {
            throw new StoredFileMissingException(key);
        }
        return new FileSystemResource(file);
    }

    @Override
    public boolean delete(String key) {
        try {
            return Files.deleteIfExists(pathOf(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete the stored file " + key, e);
        }
    }

    @Override
    public void deleteTemp(Path tempFile) {
        if (tempFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            log.warn("Could not delete temporary upload file {}: {}", tempFile, e.toString());
        }
    }

    /** The file of a valid key; anything else is refused before it becomes a path. */
    Path pathOf(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Not an attachment storage key.");
        }
        Path file = root.resolve(key.substring(0, 2)).resolve(key.substring(2, 4)).resolve(key).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("Not an attachment storage key.");
        }
        return file;
    }

    Path root() {
        return root;
    }
}