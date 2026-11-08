package team.sprintory_backend.attachment.dto;

import org.springframework.core.io.Resource;

/**
 * A stored attachment ready to stream, for the download and preview endpoints
 * (docs/attachments-docs.md §3.4, §3.5). Everything the response headers need is read before the
 * transaction ends, so streaming touches no entity.
 */
public record AttachmentFile(String fileName, String contentType, long sizeBytes, Resource content) {
}