package team.sprintory_backend.common.richtext;

/**
 * A comment, description or chat message is not an allowed TipTap document. Answered with
 * {@code 400 INVALID_RICH_TEXT}; the message says what is wrong (docs/mentions-docs.md §2).
 */
public class InvalidRichTextException extends RuntimeException {

    public InvalidRichTextException(String message) {
        super(message);
    }
}