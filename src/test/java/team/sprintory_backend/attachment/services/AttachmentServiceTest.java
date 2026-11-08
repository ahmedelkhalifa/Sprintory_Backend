package team.sprintory_backend.attachment.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import team.sprintory_backend.attachment.dto.AttachmentFile;
import team.sprintory_backend.attachment.dto.AttachmentResponse;
import team.sprintory_backend.attachment.exceptions.FileTooLargeException;
import team.sprintory_backend.attachment.exceptions.FileTypeNotAllowedException;
import team.sprintory_backend.attachment.exceptions.TooManyFilesException;
import team.sprintory_backend.attachment.models.AttachmentType;
import team.sprintory_backend.attachment.models.TaskAttachment;
import team.sprintory_backend.attachment.repositories.TaskAttachmentRepository;
import team.sprintory_backend.attachment.storage.LocalAttachmentStorage;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.project.services.ProjectAccessResult;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.task.services.TaskStatusHistoryService;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.exceptions.WorkspaceArchivedException;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static team.sprintory_backend.project.models.ProjectPermission.EDIT_TASKS;
import static team.sprintory_backend.project.models.ProjectPermission.MODERATE_COMMENTS;
import static team.sprintory_backend.project.models.ProjectPermission.VIEW_WORK;

/**
 * The upload and list rules of docs/attachments-docs.md. Access, repositories and history are
 * mocked; the type check, the WebP conversion and the disk storage are the real ones, on a
 * temporary folder.
 */
class AttachmentServiceTest {

    private static final Long PROJECT_ID = 3L;
    private static final Long TASK_ID = 5L;
    private static final byte[] PDF = "%PDF-1.7\nhello\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EXE = "MZ\u0090\u0000 this program".getBytes(StandardCharsets.ISO_8859_1);

    @TempDir
    Path dir;

    private ProjectAccess access;
    private TaskRepository taskRepository;
    private TaskAttachmentRepository attachmentRepository;
    private TaskStatusHistoryService history;
    private LocalAttachmentStorage storage;
    private AttachmentService service;

    private final User ada = User.builder().id(42L).firstName("Ada").lastName("Lovelace").build();
    private final User grace = User.builder().id(9L).firstName("Grace").lastName("Hopper").build();
    private Workspace workspace;
    private Project project;
    private Task task;
    private long nextId = 90;

    @BeforeEach
    void setUp() {
        access = mock(ProjectAccess.class);
        taskRepository = mock(TaskRepository.class);
        attachmentRepository = mock(TaskAttachmentRepository.class);
        history = mock(TaskStatusHistoryService.class);
        storage = new LocalAttachmentStorage(dir.toString());
        service = new AttachmentService(access, taskRepository, attachmentRepository, new FileTypeDetector(),
                new ImageOptimizer(2560, 0.80f, 40_000_000L, 2), storage, history);

        workspace = Workspace.builder().id(8L).name("Acme").slug("acme").build();
        project = Project.builder().id(PROJECT_ID).workspace(workspace).name("Mobile App").key("MOB").build();
        task = Task.builder().id(TASK_ID).project(project).key("MOB-5").title("Fix login").build();
        when(taskRepository.findByIdAndProjectId(TASK_ID, PROJECT_ID)).thenReturn(Optional.of(task));
        when(attachmentRepository.save(any(TaskAttachment.class))).thenAnswer(call -> {
            TaskAttachment saved = call.getArgument(0);
            saved.setId(++nextId);
            return saved;
        });
        // The service registers a rollback clean-up, as it does inside the real transaction.
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    // --- upload ---

    @Test
    void aMemberUploadsAPdfAndItIsStoredUnderARandomKey() throws IOException {
        givenActive(ada, EDIT_TASKS);

        List<AttachmentResponse> responses = service.upload(ada, PROJECT_ID, TASK_ID,
                List.of(file("Requirements v2.pdf", PDF)));

        TaskAttachment saved = savedAttachments().get(0);
        assertSame(task, saved.getTask());
        assertSame(ada, saved.getUploader());
        assertEquals("Requirements v2.pdf", saved.getFileName());
        assertEquals(AttachmentType.PDF, saved.getFileType());
        assertEquals(PDF.length, saved.getSizeBytes());
        assertEquals(PDF.length, saved.getOriginalSizeBytes());
        assertArrayEquals(PDF, storage.load(saved.getStorageKey()).getContentAsByteArray());
        verify(history).recordAttachmentAdded(task, saved, ada);

        AttachmentResponse response = responses.get(0);
        assertEquals(saved.getId(), response.id());
        assertEquals(TASK_ID, response.taskId());
        assertEquals("application/pdf", response.contentType());
        assertFalse(response.image());
        assertEquals(new AttachmentResponse.Uploader(42L, "Ada Lovelace"), response.uploader());
        assertEquals("/api/v1/attachments/" + saved.getId(), response.downloadUrl());
        assertNull(response.previewUrl());
        assertTrue(response.canDelete(), "the uploader with EDIT_TASKS can delete their own");
        assertEquals(List.of(), tempFiles(), "no temporary file is left");
    }

    @Test
    void anImageIsStoredAsASmallerWebpUnderAWebpName() throws IOException {
        givenActive(ada, EDIT_TASKS);
        byte[] png = png(800, 600);

        AttachmentResponse response = service.upload(ada, PROJECT_ID, TASK_ID, List.of(file("diagram.png", png))).get(0);

        TaskAttachment saved = savedAttachments().get(0);
        assertEquals("diagram.webp", saved.getFileName());
        assertEquals(AttachmentType.WEBP, saved.getFileType());
        assertEquals(png.length, saved.getOriginalSizeBytes());
        assertTrue(saved.getSizeBytes() < png.length);
        assertEquals(saved.getSizeBytes(), storage.load(saved.getStorageKey()).contentLength());
        assertEquals("image/webp", response.contentType());
        assertTrue(response.image());
        assertEquals(response.downloadUrl() + "/preview", response.previewUrl());
        assertEquals(List.of(), tempFiles());
    }

    @Test
    void severalFilesAreSavedInTheOrderSentWithOneHistoryEntryEach() {
        givenActive(ada, EDIT_TASKS);

        List<AttachmentResponse> responses = service.upload(ada, PROJECT_ID, TASK_ID, List.of(
                file("a.pdf", PDF), file("notes.txt", "hello".getBytes(StandardCharsets.UTF_8)), file("c.pdf", PDF)));

        assertEquals(List.of("a.pdf", "notes.txt", "c.pdf"), responses.stream().map(AttachmentResponse::fileName).toList());
        verify(history, times(3)).recordAttachmentAdded(any(), any(), any());
        assertEquals(3, storedFiles().size());
    }

    @Test
    void aPathInTheNameIsDroppedAndNeverReachesTheDisk() throws IOException {
        givenActive(ada, EDIT_TASKS);

        AttachmentResponse response = service.upload(ada, PROJECT_ID, TASK_ID,
                List.of(file("../../x.pdf", PDF))).get(0);

        assertEquals("x.pdf", response.fileName());
        try (Stream<Path> all = Files.walk(dir)) {
            assertTrue(all.noneMatch(path -> path.getFileName().toString().equals("x.pdf")));
        }
    }

    @Test
    void oneRefusedFileRefusesTheWholeUploadAndLeavesNothing() throws IOException {
        givenActive(ada, EDIT_TASKS);

        FileTypeNotAllowedException e = assertThrows(FileTypeNotAllowedException.class,
                () -> service.upload(ada, PROJECT_ID, TASK_ID,
                        List.of(file("ok.pdf", PDF), file("invoice.pdf", EXE), file("later.pdf", PDF))));

        assertEquals("invoice.pdf: the file's content does not match its extension.", e.getMessage());
        verify(attachmentRepository, never()).save(any());
        verifyNoInteractions(history);
        assertEquals(List.of(), storedFiles());
        assertEquals(List.of(), tempFiles());
    }

    @Test
    void storedFilesAreDeletedAgainWhenTheTransactionRollsBack() {
        givenActive(ada, EDIT_TASKS);
        service.upload(ada, PROJECT_ID, TASK_ID, List.of(file("a.pdf", PDF), file("b.pdf", PDF)));
        assertEquals(2, storedFiles().size());

        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertEquals(List.of(), storedFiles());
    }

    @Test
    void storedFilesStayWhenTheTransactionCommits() {
        givenActive(ada, EDIT_TASKS);
        service.upload(ada, PROJECT_ID, TASK_ID, List.of(file("a.pdf", PDF)));

        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

        assertEquals(1, storedFiles().size());
    }

    @Test
    void refusesMoreThanFiveFilesBeforeReadingAny() {
        givenActive(ada, EDIT_TASKS);
        List<MultipartFile> six = Stream.generate(() -> (MultipartFile) file("a.pdf", PDF)).limit(6).toList();

        TooManyFilesException e = assertThrows(TooManyFilesException.class,
                () -> service.upload(ada, PROJECT_ID, TASK_ID, six));

        assertEquals("You can upload at most 5 files at once.", e.getMessage());
        assertEquals(List.of(), tempFiles());
    }

    @Test
    void refusesAFileOverTwentyMegabytes() {
        givenActive(ada, EDIT_TASKS);
        MultipartFile big = mock(MultipartFile.class);
        when(big.getOriginalFilename()).thenReturn("video.zip");
        when(big.getSize()).thenReturn(AttachmentService.MAX_FILE_BYTES + 1);

        FileTooLargeException e = assertThrows(FileTooLargeException.class,
                () -> service.upload(ada, PROJECT_ID, TASK_ID, List.of(file("a.pdf", PDF), big)));

        assertEquals("File too large (max 20 MB).", e.getMessage());
        verify(attachmentRepository, never()).save(any());
        assertEquals(List.of(), tempFiles());
    }

    @Test
    void refusesAnEmptyRequestAndAnEmptyFile() {
        givenActive(ada, EDIT_TASKS);

        BadRequestException none = assertThrows(BadRequestException.class,
                () -> service.upload(ada, PROJECT_ID, TASK_ID, List.of()));
        BadRequestException missing = assertThrows(BadRequestException.class,
                () -> service.upload(ada, PROJECT_ID, TASK_ID, null));
        BadRequestException empty = assertThrows(BadRequestException.class,
                () -> service.upload(ada, PROJECT_ID, TASK_ID, List.of(file("blank.txt", new byte[0]))));

        assertEquals("Choose at least one file to upload.", none.getMessage());
        assertEquals("Choose at least one file to upload.", missing.getMessage());
        assertEquals("blank.txt is empty.", empty.getMessage());
    }

    @Test
    void aViewerOrAnArchivedWorkspaceIsRefusedBeforeAnythingHappens() {
        when(access.requireActive(PROJECT_ID, grace, EDIT_TASKS)).thenThrow(new AccessDeniedException("no"));

        assertThrows(AccessDeniedException.class,
                () -> service.upload(grace, PROJECT_ID, TASK_ID, List.of(file("a.pdf", PDF))));

        verifyNoInteractions(taskRepository, attachmentRepository, history);
        assertEquals(List.of(), tempFiles());
    }

    @Test
    void aMissingOrDeletedTaskIsNotFound() {
        givenActive(ada, EDIT_TASKS);
        when(taskRepository.findByIdAndProjectId(77L, PROJECT_ID)).thenReturn(Optional.empty());

        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class,
                () -> service.upload(ada, PROJECT_ID, 77L, List.of(file("a.pdf", PDF))));

        assertEquals("Task not found.", e.getMessage());
        assertEquals(List.of(), tempFiles());
    }

    // --- list ---

    @Test
    void listShowsTheTasksAttachmentsWithWhoMayDeleteThem() {
        givenVisible(grace, VIEW_WORK, EDIT_TASKS);
        TaskAttachment own = attachment(1L, grace, AttachmentType.PDF);
        TaskAttachment others = attachment(2L, ada, AttachmentType.JPEG);
        TaskAttachment orphan = attachment(3L, null, AttachmentType.ZIP);
        when(attachmentRepository.findByTaskIdOrderByCreatedAtDescIdDesc(TASK_ID)).thenReturn(List.of(own, others, orphan));

        List<AttachmentResponse> list = service.list(grace, PROJECT_ID, TASK_ID);

        assertEquals(List.of(1L, 2L, 3L), list.stream().map(AttachmentResponse::id).toList());
        assertEquals(List.of(true, false, false), list.stream().map(AttachmentResponse::canDelete).toList());
        assertTrue(list.get(1).image());
        assertEquals("/api/v1/attachments/2/preview", list.get(1).previewUrl());
        assertNull(list.get(2).uploader(), "the uploader's account is gone");
    }

    @Test
    void theProjectManagerMayDeleteEveryAttachment() {
        givenVisible(grace, VIEW_WORK, EDIT_TASKS, MODERATE_COMMENTS);
        when(attachmentRepository.findByTaskIdOrderByCreatedAtDescIdDesc(TASK_ID)).thenReturn(List.of(
                attachment(1L, ada, AttachmentType.PDF), attachment(2L, null, AttachmentType.PDF)));

        assertTrue(service.list(grace, PROJECT_ID, TASK_ID).stream().allMatch(AttachmentResponse::canDelete));
    }

    @Test
    void aViewerAndAnArchivedWorkspaceGetNoDeleteButton() {
        givenVisible(grace, VIEW_WORK);
        when(attachmentRepository.findByTaskIdOrderByCreatedAtDescIdDesc(TASK_ID))
                .thenReturn(List.of(attachment(1L, grace, AttachmentType.PDF)));
        assertFalse(service.list(grace, PROJECT_ID, TASK_ID).get(0).canDelete(), "a Viewer lost EDIT_TASKS");

        workspace.setArchivedAt(Instant.now());
        givenVisible(grace, VIEW_WORK, EDIT_TASKS, MODERATE_COMMENTS);
        assertFalse(service.list(grace, PROJECT_ID, TASK_ID).get(0).canDelete(), "archived is read-only");
    }

    @Test
    void listOfAMissingTaskIsNotFound() {
        givenVisible(ada, VIEW_WORK);
        when(taskRepository.findByIdAndProjectId(77L, PROJECT_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.list(ada, PROJECT_ID, 77L));
        verifyNoInteractions(attachmentRepository);
    }

    // --- download and preview ---

    @Test
    void aViewerDownloadsTheStoredFile() throws IOException {
        givenVisible(grace, VIEW_WORK);
        TaskAttachment pdf = givenStored(1L, ada, AttachmentType.PDF, PDF);

        AttachmentFile file = service.download(grace, 1L);

        assertEquals(pdf.getFileName(), file.fileName());
        assertEquals("application/pdf", file.contentType());
        assertEquals(PDF.length, file.sizeBytes());
        assertArrayEquals(PDF, file.content().getContentAsByteArray());
    }

    @Test
    void imagesHaveAPreviewAndOtherFilesDoNot() throws IOException {
        givenVisible(grace, VIEW_WORK);
        byte[] png = png(40, 30);
        givenStored(1L, ada, AttachmentType.PNG, png);
        givenStored(2L, ada, AttachmentType.PDF, PDF);

        AttachmentFile preview = service.preview(grace, 1L);
        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class, () -> service.preview(grace, 2L));

        assertEquals("image/png", preview.contentType());
        assertArrayEquals(png, preview.content().getContentAsByteArray());
        assertEquals("Attachment not found.", e.getMessage());
    }

    @Test
    void aMissingOrDeletedAttachmentOrOneOnADeletedTaskIsNotFound() {
        when(attachmentRepository.findLiveWithTaskById(1L)).thenReturn(Optional.empty());

        for (var call : List.<org.junit.jupiter.api.function.Executable>of(
                () -> service.download(ada, 1L), () -> service.preview(ada, 1L), () -> service.delete(ada, 1L))) {
            ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class, call);
            assertEquals("Attachment not found.", e.getMessage());
        }
        verifyNoInteractions(access, history);
    }

    @Test
    void someoneWhoCannotSeeTheProjectGetsTheSameNotFoundAsForAMissingAttachment() throws IOException {
        givenStored(1L, ada, AttachmentType.PDF, PDF);
        when(access.require(PROJECT_ID, grace, VIEW_WORK)).thenThrow(new ResourceNotFoundException("Project not found."));

        for (var call : List.<org.junit.jupiter.api.function.Executable>of(
                () -> service.download(grace, 1L), () -> service.preview(grace, 1L), () -> service.delete(grace, 1L))) {
            ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class, call);
            assertEquals("Attachment not found.", e.getMessage(), "nothing tells it apart from a missing one");
        }
    }

    @Test
    void aWorkspaceAdminOutsideTheProjectIsForbidden() throws IOException {
        givenStored(1L, ada, AttachmentType.PDF, PDF);
        when(access.require(PROJECT_ID, grace, VIEW_WORK)).thenThrow(new AccessDeniedException("no VIEW_WORK"));

        assertThrows(AccessDeniedException.class, () -> service.download(grace, 1L));
        assertThrows(AccessDeniedException.class, () -> service.delete(grace, 1L));
        verifyNoInteractions(history);
    }

    // --- delete ---

    @Test
    void theUploaderDeletesTheirOwnAttachmentAndHistoryRecordsIt() throws IOException {
        givenVisible(ada, VIEW_WORK, EDIT_TASKS);
        TaskAttachment own = givenStored(1L, ada, AttachmentType.PDF, PDF);

        service.delete(ada, 1L);

        assertNotNull(own.getDeletedAt());
        assertEquals(42L, own.getDeletedById());
        verify(attachmentRepository).save(own);
        verify(history).recordAttachmentRemoved(task, own, ada);
        assertEquals(1, storedFiles().size(), "the file stays until the nightly clean-up");
    }

    @Test
    void theProjectManagerDeletesAnyonesAttachmentEvenOfADeletedAccount() throws IOException {
        givenVisible(grace, VIEW_WORK, EDIT_TASKS, MODERATE_COMMENTS);
        TaskAttachment others = givenStored(1L, ada, AttachmentType.PDF, PDF);
        TaskAttachment orphan = givenStored(2L, null, AttachmentType.PDF, PDF);

        service.delete(grace, 1L);
        service.delete(grace, 2L);

        assertNotNull(others.getDeletedAt());
        assertNotNull(orphan.getDeletedAt());
        assertEquals(9L, others.getDeletedById());
        verify(history).recordAttachmentRemoved(task, others, grace);
        verify(history).recordAttachmentRemoved(task, orphan, grace);
    }

    @Test
    void anotherMemberCannotDeleteSomeoneElsesAttachment() throws IOException {
        givenVisible(grace, VIEW_WORK, EDIT_TASKS);
        TaskAttachment others = givenStored(1L, ada, AttachmentType.PDF, PDF);

        AccessDeniedException e = assertThrows(AccessDeniedException.class, () -> service.delete(grace, 1L));

        assertEquals("Only the uploader or the Project Manager can delete an attachment.", e.getMessage());
        assertNull(others.getDeletedAt());
        verify(attachmentRepository, never()).save(any());
        verifyNoInteractions(history);
    }

    @Test
    void anUploaderWhoBecameAViewerCannotDeleteTheirOldAttachment() throws IOException {
        givenVisible(ada, VIEW_WORK);
        TaskAttachment own = givenStored(1L, ada, AttachmentType.PDF, PDF);

        assertThrows(AccessDeniedException.class, () -> service.delete(ada, 1L));
        assertNull(own.getDeletedAt());
    }

    @Test
    void nobodyDeletesInAnArchivedWorkspace() throws IOException {
        workspace.setArchivedAt(Instant.now());
        givenVisible(grace, VIEW_WORK, EDIT_TASKS, MODERATE_COMMENTS);
        TaskAttachment others = givenStored(1L, ada, AttachmentType.PDF, PDF);

        assertThrows(WorkspaceArchivedException.class, () -> service.delete(grace, 1L));

        assertNull(others.getDeletedAt());
        verify(attachmentRepository, never()).save(any());
        verifyNoInteractions(history);
    }

    // --- helpers ---

    /** An attachment of the task whose file really is in the store. */
    private TaskAttachment givenStored(Long id, User uploader, AttachmentType type, byte[] content) throws IOException {
        Path temp = storage.createTempFile();
        Files.write(temp, content);
        TaskAttachment attachment = TaskAttachment.builder().id(id).task(task).uploader(uploader)
                .fileName("file-" + id + "." + type.extensions().get(0)).fileType(type)
                .sizeBytes((long) content.length).originalSizeBytes((long) content.length)
                .storageKey(storage.store(temp)).build();
        when(attachmentRepository.findLiveWithTaskById(id)).thenReturn(Optional.of(attachment));
        return attachment;
    }

    private void givenActive(User user, ProjectPermission... permissions) {
        when(access.requireActive(PROJECT_ID, user, permissions[0])).thenReturn(result(permissions));
    }

    private void givenVisible(User user, ProjectPermission... permissions) {
        when(access.require(PROJECT_ID, user, VIEW_WORK)).thenReturn(result(permissions));
    }

    private ProjectAccessResult result(ProjectPermission... permissions) {
        WorkspaceMember member = WorkspaceMember.builder().workspace(workspace).build();
        return new ProjectAccessResult(project, member, null, Set.of(permissions));
    }

    private TaskAttachment attachment(Long id, User uploader, AttachmentType type) {
        return TaskAttachment.builder().id(id).task(task).uploader(uploader).fileName("file." + type.extensions().get(0))
                .fileType(type).sizeBytes(10L).originalSizeBytes(10L)
                .storageKey("3fa2b6c1-0d4e-4f7a-9b8c-1d2e3f4a5b6" + id).build();
    }

    private List<TaskAttachment> savedAttachments() {
        ArgumentCaptor<TaskAttachment> saved = ArgumentCaptor.forClass(TaskAttachment.class);
        verify(attachmentRepository, atLeastOnce()).save(saved.capture());
        return saved.getAllValues();
    }

    private static void completeTransaction(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(status));
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("files", name, "application/octet-stream", content);
    }

    /** Stored files: everything under the root except the temp folder. */
    private List<Path> storedFiles() {
        try (Stream<Path> all = Files.walk(dir)) {
            return all.filter(Files::isRegularFile)
                    .filter(path -> !path.getParent().getFileName().toString().equals(".tmp"))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Path> tempFiles() {
        try (Stream<Path> all = Files.list(dir.resolve(".tmp"))) {
            return all.toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(7);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, ((x * 255 / width) << 16) | ((y * 255 / height) << 8) | random.nextInt(32));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}