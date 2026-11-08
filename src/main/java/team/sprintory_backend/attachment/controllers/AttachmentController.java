package team.sprintory_backend.attachment.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import team.sprintory_backend.attachment.dto.AttachmentFile;
import team.sprintory_backend.attachment.dto.AttachmentResponse;
import team.sprintory_backend.attachment.services.AttachmentService;
import team.sprintory_backend.security.userdetails.AppUserDetails;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * PMP-141: files attached to a task (or a subtask). Contract: docs/attachments-docs.md.
 * Uploading and listing are under the task; downloading, previewing and deleting address the
 * attachment directly, and the service finds and checks its project.
 */
@RestController
@RequiredArgsConstructor
public class AttachmentController {

    static final String TASK_ATTACHMENTS = "/api/v1/projects/{projectId}/tasks/{taskId}/attachments";
    static final String ATTACHMENT = "/api/v1/attachments/{attachmentId}";

    /** Nothing in a downloaded file may run in the page, even if a browser renders it. */
    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; sandbox";

    private final AttachmentService attachmentService;

    @GetMapping(TASK_ATTACHMENTS)
    public List<AttachmentResponse> list(@AuthenticationPrincipal AppUserDetails principal,
                                         @PathVariable Long projectId, @PathVariable Long taskId) {
        return attachmentService.list(principal.getUser(), projectId, taskId);
    }

    /**
     * {@code multipart/form-data} with one part named {@code files} per file. Without any
     * (also for a request that is not multipart) the service answers 400.
     */
    @PostMapping(TASK_ATTACHMENTS)
    public ResponseEntity<List<AttachmentResponse>> upload(@AuthenticationPrincipal AppUserDetails principal,
                                                           @PathVariable Long projectId, @PathVariable Long taskId,
                                                           @RequestParam(name = "files", required = false)
                                                           List<MultipartFile> files) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(attachmentService.upload(principal.getUser(), projectId, taskId, files));
    }

    /** Always a download, never shown in the page; not cached. */
    @GetMapping(ATTACHMENT)
    public ResponseEntity<Resource> download(@AuthenticationPrincipal AppUserDetails principal,
                                             @PathVariable Long attachmentId) {
        AttachmentFile file = attachmentService.download(principal.getUser(), attachmentId);
        return fileResponse(file, ContentDisposition.attachment(), CacheControl.noStore());
    }

    /** Images only, shown inline for thumbnails; cached briefly by the viewer's browser only. */
    @GetMapping(ATTACHMENT + "/preview")
    public ResponseEntity<Resource> preview(@AuthenticationPrincipal AppUserDetails principal,
                                            @PathVariable Long attachmentId) {
        AttachmentFile file = attachmentService.preview(principal.getUser(), attachmentId);
        return fileResponse(file, ContentDisposition.inline(), CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate());
    }

    @DeleteMapping(ATTACHMENT)
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppUserDetails principal,
                                       @PathVariable Long attachmentId) {
        attachmentService.delete(principal.getUser(), attachmentId);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<Resource> fileResponse(AttachmentFile file, ContentDisposition.Builder disposition,
                                                         CacheControl cacheControl) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        disposition.filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
                .cacheControl(cacheControl)
                .body(file.content());
    }
}