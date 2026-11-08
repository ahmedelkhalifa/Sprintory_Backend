package team.sprintory_backend.attachment.exceptions;

/** More files in one upload than allowed. Answered with {@code 400 TOO_MANY_FILES} (docs/attachments-docs.md §3.1). */
public class TooManyFilesException extends RuntimeException {

    public TooManyFilesException(String message) {
        super(message);
    }
}