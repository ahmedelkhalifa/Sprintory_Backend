package team.sprintory_backend.attachment.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAttachmentStorageTest {

    private static final byte[] CONTENT = "%PDF-1.7 hello".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path dir;

    @Test
    void createsItsFoldersAtStartup() {
        Path root = dir.resolve("data").resolve("attachments");

        new LocalAttachmentStorage(root.toString());

        assertTrue(Files.isDirectory(root.resolve(LocalAttachmentStorage.TEMP_DIR)));
    }

    @Test
    void tempFilesAreCreatedEmptyInsideTheTempFolder() throws IOException {
        LocalAttachmentStorage storage = storage();

        Path temp = storage.createTempFile();

        assertEquals(storage.root().resolve(LocalAttachmentStorage.TEMP_DIR), temp.getParent());
        assertEquals(0, Files.size(temp));
    }

    @Test
    void storeMovesTheFileUnderARandomKeyInTwoLevelsOfFolders() throws IOException {
        LocalAttachmentStorage storage = storage();
        Path temp = tempWith(storage, CONTENT);

        String key = storage.store(temp);

        assertTrue(key.matches("[0-9a-f-]{36}"), key);
        Path stored = storage.root().resolve(key.substring(0, 2)).resolve(key.substring(2, 4)).resolve(key);
        assertArrayEquals(CONTENT, Files.readAllBytes(stored));
        assertFalse(Files.exists(temp), "the temporary file was moved, not copied");
        assertNotEquals(key, storage.store(tempWith(storage, CONTENT)), "every upload gets its own key");
    }

    @Test
    void loadGivesTheStoredBytes() throws IOException {
        LocalAttachmentStorage storage = storage();
        String key = storage.store(tempWith(storage, CONTENT));

        Resource resource = storage.load(key);

        try (InputStream in = resource.getInputStream()) {
            assertArrayEquals(CONTENT, in.readAllBytes());
        }
        assertEquals(CONTENT.length, resource.contentLength());
    }

    @Test
    void loadOfAValidKeyWithoutAFileIsAServerFault() {
        LocalAttachmentStorage storage = storage();

        assertThrows(StoredFileMissingException.class,
                () -> storage.load("3fa2b6c1-0d4e-4f7a-9b8c-1d2e3f4a5b6c"));
    }

    @Test
    void deleteRemovesTheFileAndToleratesAMissingOne() throws IOException {
        LocalAttachmentStorage storage = storage();
        String key = storage.store(tempWith(storage, CONTENT));

        assertTrue(storage.delete(key));
        assertFalse(storage.delete(key));
        assertThrows(StoredFileMissingException.class, () -> storage.load(key));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../../etc/passwd", "..", "", "report.pdf", "3FA2B6C1-0D4E-4F7A-9B8C-1D2E3F4A5B6C",
            "3fa2b6c1-0d4e-4f7a-9b8c-1d2e3f4a5b6c/../../x", "3fa2b6c1/0d4e-4f7a-9b8c-1d2e3f4a5b6c",
            "..\\..\\windows\\win.ini"})
    void refusesAnythingThatIsNotAKeyBeforeTouchingTheDisk(String key) {
        LocalAttachmentStorage storage = storage();

        assertThrows(IllegalArgumentException.class, () -> storage.load(key));
        assertThrows(IllegalArgumentException.class, () -> storage.delete(key));
    }

    @Test
    void refusesANullKey() {
        assertThrows(IllegalArgumentException.class, () -> storage().load(null));
    }

    @Test
    void deleteTempIgnoresMissingAndNullFiles() throws IOException {
        LocalAttachmentStorage storage = storage();
        Path temp = tempWith(storage, CONTENT);

        storage.deleteTemp(temp);
        storage.deleteTemp(temp);
        storage.deleteTemp(null);

        assertFalse(Files.exists(temp));
    }

    @Test
    void storedFilesListsOnlyKeyFilesInTheirOwnPlace() throws IOException {
        LocalAttachmentStorage storage = storage();
        String first = storage.store(tempWith(storage, CONTENT));
        String second = storage.store(tempWith(storage, CONTENT));
        tempWith(storage, CONTENT);                                            // an upload in progress
        Files.write(storage.root().resolve("README.txt"), CONTENT);            // put there by hand
        String misplaced = "3fa2b6c1-0d4e-4f7a-9b8c-1d2e3f4a5b6c";
        Files.createDirectories(storage.root().resolve("zz"));
        Files.write(storage.root().resolve("zz").resolve(misplaced), CONTENT); // not where its key belongs

        List<String> keys;
        try (Stream<AttachmentStorage.StoredFile> files = storage.storedFiles()) {
            keys = files.map(AttachmentStorage.StoredFile::key).sorted().toList();
        }

        assertEquals(Stream.of(first, second).sorted().toList(), keys);
    }

    @Test
    void deleteTempFilesOlderThanKeepsUploadsInProgress() throws IOException {
        LocalAttachmentStorage storage = storage();
        Path crashed = tempWith(storage, CONTENT);
        Files.setLastModifiedTime(crashed, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        Path running = tempWith(storage, CONTENT);

        int deleted = storage.deleteTempFilesOlderThan(Instant.now().minus(Duration.ofDays(1)));

        assertEquals(1, deleted);
        assertFalse(Files.exists(crashed));
        assertTrue(Files.exists(running));
    }

    private LocalAttachmentStorage storage() {
        return new LocalAttachmentStorage(dir.resolve("attachments").toString());
    }

    private static Path tempWith(AttachmentStorage storage, byte[] content) throws IOException {
        Path temp = storage.createTempFile();
        Files.write(temp, content);
        return temp;
    }
}