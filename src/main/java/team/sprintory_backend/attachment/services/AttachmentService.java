package team.sprintory_backend.attachment.services;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import team.sprintory_backend.attachment.dto.AttachmentFile;
import team.sprintory_backend.attachment.dto.AttachmentResponse;
import team.sprintory_backend.attachment.exceptions.FileTooLargeException;
import team.sprintory_backend.attachment.exceptions.TooManyFilesException;
import team.sprintory_backend.attachment.models.AttachmentType;
import team.sprintory_backend.attachment.models.TaskAttachment;
import team.sprintory_backend.attachment.repositories.TaskAttachmentRepository;
import team.sprintory_backend.attachment.storage.AttachmentStorage;
import team.sprintory_backend.audit.models.AuditTarget;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.project.services.ProjectAccessResult;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.task.services.TaskStatusHistoryService;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.services.WorkspaceAccess;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Files attached to tasks (R25, PMP-141). Contract: docs/attachments-docs.md.
 *
 * <p>Listing and downloading need VIEW_WORK; uploading, and deleting one's own attachment, need
 * EDIT_TASKS in an active workspace; deleting anyone's needs MODERATE_COMMENTS (the Project
 * Manager). An upload is all or nothing: every file is checked, typed from its content and
 * compressed in a temporary file first, and only then are the files stored and the rows saved.
 * If the transaction rolls back after that, the stored files are deleted again.
 */
@Service
@RequiredArgsConstructor
public class AttachmentService {

    public static final int MAX_FILES = 5;
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    /** Also the answer to the servlet's own multipart limit (see ExceptionHandler). */
    public static final String FILE_TOO_LARGE = "File too large (max 20 MB).";

    private final ProjectAccess projectAccess;
    private final TaskRepository taskRepository;
    private final TaskAttachmentRepository attachmentRepository;
    private final FileTypeDetector detector;
    private final ImageOptimizer optimizer;
    private final AttachmentStorage storage;
    private final TaskStatusHistoryService history;

    /** The task's live attachments, newest first. */
    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(User user, Long projectId, Long taskId) {
        ProjectAccessResult access = projectAccess.require(projectId, user, ProjectPermission.VIEW_WORK);
        requireTask(projectId, taskId);
        return attachmentRepository.findByTaskIdOrderByCreatedAtDescIdDesc(taskId).stream()
                .map(attachment -> response(attachment, access, user))
                .toList();
    }

    /**
     * Attaches one to {@value #MAX_FILES} files to the task, in the order sent. One
     * {@code ATTACHMENT_ADDED} history entry is written per file. Nobody is notified.
     */
    @Transactional
    public List<AttachmentResponse> upload(User user, Long projectId, Long taskId, List<MultipartFile> files) {
        ProjectAccessResult access = projectAccess.requireActive(projectId, user, ProjectPermission.EDIT_TASKS);
        Task task = requireTask(projectId, taskId);
        checkCountAndSizes(files);

        List<PreparedFile> prepared = new ArrayList<>();
        try {
            for (MultipartFile file : files) {
                prepared.add(prepare(file));
            }
            List<String> storedKeys = new ArrayList<>();
            deleteStoredFilesOnRollback(storedKeys);

            List<AttachmentResponse> responses = new ArrayList<>();
            for (PreparedFile file : prepared) {
                String key = storage.store(file.path());
                storedKeys.add(key);
                TaskAttachment attachment = attachmentRepository.save(TaskAttachment.builder()
                        .task(task)
                        .uploader(user)
                        .fileName(file.name())
                        .fileType(file.type())
                        .sizeBytes(file.storedSize())
                        .originalSizeBytes(file.originalSize())
                        .storageKey(key)
                        .build());
                history.recordAttachmentAdded(task, attachment, user);
                responses.add(response(attachment, access, user));
            }
            return responses;
        } finally {
            // Stored files were moved away already; this removes what a refusal or failure left.
            prepared.forEach(file -> storage.deleteTemp(file.path()));
        }
    }

    /** The file for a download (§3.4). */
    @Transactional(readOnly = true)
    public AttachmentFile download(User user, Long attachmentId) {
        return open(user, attachmentId, false);
    }

    /** The image for an inline preview (§3.5); anything that is not an image is not found. */
    @Transactional(readOnly = true)
    public AttachmentFile preview(User user, Long attachmentId) {
        return open(user, attachmentId, true);
    }

    /**
     * Soft delete by the uploader (with EDIT_TASKS) or the Project Manager (MODERATE_COMMENTS),
     * in an active workspace. The file stays on disk until the nightly clean-up; the task's
     * history records the file name.
     */
    @Transactional
    public void delete(User user, Long attachmentId) {
        TaskAttachment attachment = requireAttachment(attachmentId);
        Task task = attachment.getTask();
        ProjectAccessResult access = requireAccess(task, user);
        if (!mayDelete(attachment, access, user)) {
            throw new AccessDeniedException("Only the uploader or the Project Manager can delete an attachment.");
        }
        WorkspaceAccess.requireNotArchived(access.workspaceMember());

        attachment.setDeletedAt(Instant.now());
        attachment.setDeletedById(user.getId());
        attachmentRepository.save(attachment);
        history.recordAttachmentRemoved(task, attachment, user);
    }

    // --- download steps ---

    private AttachmentFile open(User user, Long attachmentId, boolean imagesOnly) {
        TaskAttachment attachment = requireAttachment(attachmentId);
        requireAccess(attachment.getTask(), user);
        AttachmentType type = attachment.getFileType();
        if (imagesOnly && !type.isImage()) {
            throw attachmentNotFound();
        }
        return new AttachmentFile(attachment.getFileName(), type.mimeType(), attachment.getSizeBytes(),
                storage.load(attachment.getStorageKey()));
    }

    /** Missing, deleted, and on a deleted task all give the same 404. */
    private TaskAttachment requireAttachment(Long attachmentId) {
        return attachmentRepository.findLiveWithTaskById(attachmentId).orElseThrow(AttachmentService::attachmentNotFound);
    }

    /**
     * VIEW_WORK in the attachment's project. Someone who cannot see the project gets the same
     * 404 as for a missing attachment, so ids reveal nothing; a workspace admin who is not in
     * the project gets 403, as on every task endpoint.
     */
    private ProjectAccessResult requireAccess(Task task, User user) {
        try {
            return projectAccess.require(task.getProject().getId(), user, ProjectPermission.VIEW_WORK);
        } catch (ResourceNotFoundException e) {
            throw attachmentNotFound();
        }
    }

    private static ResourceNotFoundException attachmentNotFound() {
        return new ResourceNotFoundException("Attachment not found.");
    }

    // --- upload steps ---

    /** The checks that need no disk work, for every file before any is written. */
    private static void checkCountAndSizes(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new BadRequestException("Choose at least one file to upload.");
        }
        if (files.size() > MAX_FILES) {
            throw new TooManyFilesException("You can upload at most " + MAX_FILES + " files at once.");
        }
        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                throw new BadRequestException(AttachmentFileNames.displayName(file.getOriginalFilename()) + " is empty.");
            }
            if (file.getSize() > MAX_FILE_BYTES) {
                throw new FileTooLargeException(FILE_TOO_LARGE);
            }
        }
    }

    /**
     * Copies one upload into a temporary file, checks its type from the content and compresses
     * an image. The temporary file is deleted here if that fails.
     */
    private PreparedFile prepare(MultipartFile file) {
        String name = AttachmentFileNames.displayName(file.getOriginalFilename());
        Path temp = storage.createTempFile();
        try {
            long originalSize;
            try (InputStream in = file.getInputStream()) {
                originalSize = Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (originalSize > MAX_FILE_BYTES) {
                throw new FileTooLargeException(FILE_TOO_LARGE);
            }
            AttachmentType type = detector.detect(temp, name);

            Optional<Path> webp = optimizer.toWebp(temp, type);
            if (webp.isPresent()) {
                storage.deleteTemp(temp);
                temp = webp.get();
                type = AttachmentType.WEBP;
                name = AttachmentFileNames.withExtension(name, AttachmentType.WEBP.extensions().get(0));
            }
            return new PreparedFile(name, type, temp, Files.size(temp), originalSize);
        } catch (IOException e) {
            storage.deleteTemp(temp);
            throw new UncheckedIOException("Could not read the uploaded file.", e);
        } catch (RuntimeException e) {
            storage.deleteTemp(temp);
            throw e;
        }
    }

    /**
     * After the transaction ends without a commit, deletes the files stored by it, so a failed
     * upload leaves nothing on disk. The list is filled while storing.
     */
    private void deleteStoredFilesOnRollback(List<String> storedKeys) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    storedKeys.forEach(storage::delete);
                }
            }
        });
    }

    /** One upload, checked and ready to store. */
    private record PreparedFile(String name, AttachmentType type, Path path, long storedSize, long originalSize) {
    }

    // --- rules ---

    private static boolean mayDelete(TaskAttachment attachment, ProjectAccessResult access, User user) {
        return access.can(ProjectPermission.MODERATE_COMMENTS)
                || attachment.isUploadedBy(user) && access.can(ProjectPermission.EDIT_TASKS);
    }

    /** Missing, deleted and other projects' tasks all give the same 404. */
    private Task requireTask(Long projectId, Long taskId) {
        return taskRepository.findByIdAndProjectId(taskId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found."));
    }

    // --- responses ---

    private static AttachmentResponse response(TaskAttachment attachment, ProjectAccessResult access, User user) {
        AttachmentType type = attachment.getFileType();
        User uploader = attachment.getUploader();
        String downloadUrl = "/api/v1/attachments/" + attachment.getId();
        return new AttachmentResponse(
                attachment.getId(),
                attachment.getTask().getId(),
                attachment.getFileName(),
                type.mimeType(),
                attachment.getSizeBytes(),
                type.isImage(),
                uploader == null ? null : new AttachmentResponse.Uploader(uploader.getId(), AuditTarget.displayName(uploader)),
                toInstant(attachment.getCreatedAt()),
                downloadUrl,
                type.isImage() ? downloadUrl + "/preview" : null,
                !archived(access) && mayDelete(attachment, access, user));
    }

    private static boolean archived(ProjectAccessResult access) {
        return access.workspaceMember() != null && access.workspaceMember().getWorkspace().isArchived();
    }

    /** The audit columns are local date-times written in the server's zone. */
    private static Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }
}