package team.sprintory_backend.attachment.models;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The allow-list of attachment types (R25, docs/attachments-docs.md §3.1). The type is always
 * detected from the file's content; the extension must then be one of the type's extensions.
 * A new value needs a migration changing {@code ck_task_attachments_file_type}.
 */
public enum AttachmentType {
    PDF("application/pdf", false, "pdf"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", false, "docx"),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", false, "xlsx"),
    PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation", false, "pptx"),
    TXT("text/plain", false, "txt"),
    MD("text/markdown", false, "md"),
    CSV("text/csv", false, "csv"),
    PNG("image/png", true, "png"),
    JPEG("image/jpeg", true, "jpg", "jpeg"),
    GIF("image/gif", true, "gif"),
    WEBP("image/webp", true, "webp"),
    ZIP("application/zip", false, "zip");

    private final String mimeType;
    private final boolean image;
    private final List<String> extensions;

    AttachmentType(String mimeType, boolean image, String... extensions) {
        this.mimeType = mimeType;
        this.image = image;
        this.extensions = List.of(extensions);
    }

    /** Sent as {@code Content-Type} on download and preview. */
    public String mimeType() {
        return mimeType;
    }

    /** Has an inline preview (docs/attachments-docs.md §3.5). */
    public boolean isImage() {
        return image;
    }

    /** Lower case, without the dot; the first one is the usual one. */
    public List<String> extensions() {
        return extensions;
    }

    public boolean hasExtension(String extension) {
        return extension != null && extensions.contains(extension.toLowerCase(Locale.ROOT));
    }

    /** The type whose extensions include this one (ignoring case), if any. */
    public static Optional<AttachmentType> fromExtension(String extension) {
        return Arrays.stream(values()).filter(type -> type.hasExtension(extension)).findFirst();
    }
}