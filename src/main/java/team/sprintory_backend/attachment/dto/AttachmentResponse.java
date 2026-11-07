package team.sprintory_backend.attachment.dto;

import java.time.Instant;

/**
 * One attachment (docs/attachments-docs.md §2). The URLs are paths on the API; {@code previewUrl}
 * is null unless {@code image}. {@code canDelete} is for the caller and only shows or hides the
 * button: the backend checks again.
 */
public record AttachmentResponse(Long id,
                                 Long taskId,
                                 String fileName,
                                 String contentType,
                                 long sizeBytes,
                                 boolean image,
                                 Uploader uploader,
                                 Instant uploadedAt,
                                 String downloadUrl,
                                 String previewUrl,
                                 boolean canDelete) {

    /** Null in the response when the uploader's account no longer exists. */
    public record Uploader(Long id, String name) {
    }
}