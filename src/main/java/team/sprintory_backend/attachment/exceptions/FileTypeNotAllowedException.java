package team.sprintory_backend.attachment.exceptions;

/**
 * An uploaded file is not on the allow-list, its content does not match its extension, or it is
 * an Office file with macros. Answered with {@code 415 FILE_TYPE_NOT_ALLOWED}
 * (docs/attachments-docs.md §3.1). The message starts with the file's display name.
 */
public class FileTypeNotAllowedException extends RuntimeException {

    public FileTypeNotAllowedException(String message) {
        super(message);
    }
}