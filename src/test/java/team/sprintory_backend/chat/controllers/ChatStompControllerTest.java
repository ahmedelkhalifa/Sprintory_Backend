package team.sprintory_backend.chat.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.security.access.AccessDeniedException;
import team.sprintory_backend.common.richtext.InvalidRichTextException;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ErrorCode;
import team.sprintory_backend.exceptionHandler.ErrorResponse;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.mention.exceptions.InvalidMentionException;
import team.sprintory_backend.workspace.exceptions.WorkspaceArchivedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** A refused chat action is reported with the same status and code as the REST API would use. */
class ChatStompControllerTest {
    private static final String SEND = "/app/projects/5/chat.send";

    @Test
    void eachRefusalKeepsItsRestStatusAndCode() {
        assertError(new InvalidRichTextException("Unknown node: script."), 400, ErrorCode.INVALID_RICH_TEXT,
                "Unknown node: script.");
        assertError(new InvalidMentionException("Only members of this project can be mentioned."), 400,
                ErrorCode.INVALID_MENTION, "Only members of this project can be mentioned.");
        assertError(new BadRequestException("limit must be 1..100."), 400, ErrorCode.BAD_REQUEST, "limit must be 1..100.");
        assertError(new MessageConversionException("cannot read"), 400, ErrorCode.BAD_REQUEST, "Malformed message body.");
        assertError(new AccessDeniedException("Only the author may edit this message."), 403, ErrorCode.ACCESS_DENIED,
                "You do not have access to this resource.");
        assertError(new ResourceNotFoundException("Chat message not found."), 404, ErrorCode.RESOURCE_NOT_FOUND,
                "Chat message not found.");
        assertError(new WorkspaceArchivedException("archived"), 409, ErrorCode.WORKSPACE_ARCHIVED,
                "This workspace is archived. Restore it to make changes.");
    }

    @Test
    void anUnexpectedErrorRevealsNoDetails() {
        assertError(new IllegalStateException("SQL state 23505 on project_chat_messages"), 500,
                ErrorCode.INTERNAL_ERROR, "Something went wrong. Please try again.");
    }

    private static void assertError(Exception e, int status, ErrorCode code, String message) {
        ErrorResponse error = ChatStompController.error(e, SEND);
        assertEquals(status, error.getStatus());
        assertEquals(code, error.getCode());
        assertEquals(message, error.getMessage());
        assertEquals(SEND, error.getPath());
        assertNotNull(error.getTimestamp());
    }
}