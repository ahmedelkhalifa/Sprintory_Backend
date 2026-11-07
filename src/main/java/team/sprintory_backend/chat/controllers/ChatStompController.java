package team.sprintory_backend.chat.controllers;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import team.sprintory_backend.chat.dto.ChatEditRequest;
import team.sprintory_backend.chat.dto.ChatMessageIdRequest;
import team.sprintory_backend.chat.dto.ChatMessageRequest;
import team.sprintory_backend.chat.services.ChatService;
import team.sprintory_backend.common.richtext.InvalidRichTextException;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ErrorCode;
import team.sprintory_backend.exceptionHandler.ErrorResponse;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.mention.exceptions.InvalidMentionException;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.workspace.exceptions.WorkspaceArchivedException;

import java.security.Principal;
import java.time.Instant;

/** Writes arrive through STOMP; the service broadcasts only AFTER database commit. */
@Controller
@RequiredArgsConstructor
public class ChatStompController {
    /** Where a refused send, edit or delete is reported; only the sending session receives it. */
    public static final String ERROR_QUEUE = "/queue/errors";

    private static final Logger log = LoggerFactory.getLogger(ChatStompController.class);

    private final ChatService chatService;

    @MessageMapping("/projects/{projectId}/chat.send")
    public void send(@DestinationVariable Long projectId, ChatMessageRequest request, Principal principal) {
        chatService.send(caller(principal).getUser(), projectId, request.content());
    }

    @MessageMapping("/projects/{projectId}/chat.edit")
    public void edit(@DestinationVariable Long projectId, ChatEditRequest request, Principal principal) {
        chatService.edit(caller(principal).getUser(), projectId, request.messageId(), request.content());
    }

    @MessageMapping("/projects/{projectId}/chat.delete")
    public void delete(@DestinationVariable Long projectId, ChatMessageIdRequest request, Principal principal) {
        chatService.delete(caller(principal).getUser(), projectId, request.messageId());
    }

    /**
     * Sends the refusal to {@code /user/queue/errors} of the sending session only, in the same
     * shape and with the same codes as the REST API (docs/api-conventions.md §4). Without this the
     * exception would only be logged and the sender would never learn their message was refused.
     */
    @MessageExceptionHandler
    @SendToUser(destinations = ERROR_QUEUE, broadcast = false)
    public ErrorResponse handle(Exception e, SimpMessageHeaderAccessor headers) {
        return error(e, headers.getDestination());
    }

    /** The REST mapping of `exceptionHandler/ExceptionHandler`, for the exceptions chat can throw. */
    static ErrorResponse error(Exception e, String destination) {
        if (e instanceof InvalidRichTextException) {
            return body(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_RICH_TEXT, e.getMessage(), destination);
        }
        if (e instanceof InvalidMentionException) {
            return body(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_MENTION, e.getMessage(), destination);
        }
        if (e instanceof BadRequestException) {
            return body(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, e.getMessage(), destination);
        }
        if (e instanceof MessageConversionException) {
            return body(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "Malformed message body.", destination);
        }
        if (e instanceof AccessDeniedException) {
            return body(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED,
                    "You do not have access to this resource.", destination);
        }
        if (e instanceof ResourceNotFoundException) {
            return body(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, e.getMessage(), destination);
        }
        if (e instanceof WorkspaceArchivedException) {
            return body(HttpStatus.CONFLICT, ErrorCode.WORKSPACE_ARCHIVED,
                    "This workspace is archived. Restore it to make changes.", destination);
        }
        log.error("Unhandled exception on STOMP {}", destination, e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "Something went wrong. Please try again.", destination);
    }

    private static ErrorResponse body(HttpStatus status, ErrorCode code, String message, String destination) {
        return ErrorResponse.builder().timestamp(Instant.now()).status(status.value()).code(code)
                .message(message).path(destination).build();
    }

    private static AppUserDetails caller(Principal principal) {
        var auth = (org.springframework.security.core.Authentication) principal;
        return (AppUserDetails) auth.getPrincipal();
    }
}