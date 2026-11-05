package team.sprintory_backend.chat.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;
import team.sprintory_backend.chat.dto.ChatEditRequest;
import team.sprintory_backend.chat.dto.ChatMessageIdRequest;
import team.sprintory_backend.chat.dto.ChatMessageRequest;
import team.sprintory_backend.chat.services.ChatService;
import team.sprintory_backend.security.userdetails.AppUserDetails;

import java.security.Principal;

/** Writes arrive through STOMP; the service broadcasts only AFTER database commit. */
@Controller
@RequiredArgsConstructor
public class ChatStompController {
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

    private static AppUserDetails caller(Principal principal) {
        var auth = (org.springframework.security.core.Authentication) principal;
        return (AppUserDetails) auth.getPrincipal();
    }
}
