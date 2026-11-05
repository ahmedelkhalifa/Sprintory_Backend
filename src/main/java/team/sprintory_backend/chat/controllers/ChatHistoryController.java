package team.sprintory_backend.chat.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import team.sprintory_backend.chat.dto.ChatHistoryResponse;
import team.sprintory_backend.chat.services.ChatService;
import team.sprintory_backend.security.userdetails.AppUserDetails;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/chat")
@RequiredArgsConstructor
public class ChatHistoryController {
    private final ChatService chatService;

    @GetMapping("/messages")
    public ChatHistoryResponse history(@AuthenticationPrincipal AppUserDetails principal,
                                       @PathVariable Long projectId,
                                       @RequestParam(required = false) Long before,
                                       @RequestParam(defaultValue = "50") int limit) {
        return chatService.history(principal.getUser(), projectId, before, limit);
    }
}
