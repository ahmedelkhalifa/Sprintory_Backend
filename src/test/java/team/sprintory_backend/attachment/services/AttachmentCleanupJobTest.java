package team.sprintory_backend.attachment.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import team.sprintory_backend.attachment.repositories.TaskAttachmentRepository;
import team.sprintory_backend.attachment.storage.LocalAttachmentStorage;
import team.sprintory_backend.attachment.storage.StoredFileMissingException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The nightly clean-up with the repository mocked and the real disk storage on a temporary folder. */
class AttachmentCleanupJobTest {

    private static final byte[] CONTENT = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);
    private static final Instant THREE_DAYS_AGO = Instant.now().minus(Duration.ofDays(3));

    @TempDir
    Path dir;

    private TaskAttachmentRepository repository;
    private LocalAttachmentStorage storage;
    private AttachmentCleanupJob job;

    @BeforeEach
    void setUp() {
        repository = mock(TaskAttachmentRepository.class);
        storage = new LocalAttachmentStorage(dir.toString());
        job = new AttachmentCleanupJob(repository, storage, 30);
    }

    @Test
    void removesRowsDeletedMoreThanTheRetentionAgo() {
        when(repository.deleteDeletedBefore(any())).thenReturn(4);

        Instant before = Instant.now();
        AttachmentCleanupJob.Result result = job.run();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteDeletedBefore(cutoff.capture());
        Duration age = Duration.between(cutoff.getValue(), before);
        assertTrue(age.compareTo(Duration.ofDays(30).minusSeconds(5)) > 0 && age.compareTo(Duration.ofDays(30).plusSeconds(5)) < 0,
                "cutoff is 30 days back, was " + age);
        assertEquals(4, result.rows());
    }

    @Test
    void deletesOldFilesWithoutARowAndKeepsTheRest() throws IOException {
        String referenced = stored(THREE_DAYS_AGO);
        String orphan = stored(THREE_DAYS_AGO);
        String justStored = stored(Instant.now());                 // its row may not be committed yet
        when(repository.findAllStorageKeys()).thenReturn(List.of(referenced));

        AttachmentCleanupJob.Result result = job.run();

        assertEquals(1, result.files());
        assertExists(referenced);
        assertExists(justStored);
        assertThrows(StoredFileMissingException.class, () -> storage.load(orphan));
    }

    @Test
    void readsTheKeysAfterRemovingRowsSoTheirFilesGoInTheSameRun() throws IOException {
        String expired = stored(THREE_DAYS_AGO);
        when(repository.findAllStorageKeys()).thenReturn(List.of());   // its row was just removed

        job.run();

        var order = inOrder(repository);
        order.verify(repository).deleteDeletedBefore(any());
        order.verify(repository).findAllStorageKeys();
        assertThrows(StoredFileMissingException.class, () -> storage.load(expired));
    }

    @Test
    void deletesTemporaryFilesOfCrashedUploadsOnly() throws IOException {
        Path crashed = storage.createTempFile();
        Files.setLastModifiedTime(crashed, FileTime.from(THREE_DAYS_AGO));
        Path running = storage.createTempFile();

        AttachmentCleanupJob.Result result = job.run();

        assertEquals(1, result.tempFiles());
        assertFalse(Files.exists(crashed));
        assertTrue(Files.exists(running));
    }

    @Test
    void neverTouchesFilesThatAreNotNamedLikeAKey() throws IOException {
        Path note = dir.resolve("README.txt");
        Files.write(note, CONTENT);
        Files.setLastModifiedTime(note, FileTime.from(THREE_DAYS_AGO));

        assertEquals(new AttachmentCleanupJob.Result(0, 0, 0), job.run());
        assertTrue(Files.exists(note));
    }

    private String stored(Instant lastModified) throws IOException {
        Path temp = storage.createTempFile();
        Files.write(temp, CONTENT);
        Files.setLastModifiedTime(temp, FileTime.from(lastModified));
        return storage.store(temp);
    }

    private void assertExists(String key) throws IOException {
        assertArrayEquals(CONTENT, storage.load(key).getContentAsByteArray());
    }
}