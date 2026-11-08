package team.sprintory_backend.attachment.exceptions;

/**
 * An uploaded file is over the size limit. Answered with {@code 413 FILE_TOO_LARGE}
 * (docs/attachments-docs.md §3.1), the same as when the servlet's own limit refuses the request.
 */
public class FileTooLargeException extends RuntimeException {

    public FileTooLargeException(String message) {
        super(message);
    }
}