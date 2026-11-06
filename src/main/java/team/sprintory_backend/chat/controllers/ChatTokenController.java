package team.sprintory_backend.chat.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import team.sprintory_backend.chat.dto.ChatSocketTokenResponse;
import team.sprintory_backend.security.jwt.JwtService;
import team.sprintory_backend.security.userdetails.AppUserDetails;

/** Existing HttpOnly cookie grants a separate 2-minute STOMP CONNECT credential. */
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatTokenController {
    private final JwtService jwtService;

    @PostMapping("/socket-token")
    public ChatSocketTokenResponse issue(@AuthenticationPrincipal AppUserDetails principal) {
        return new ChatSocketTokenResponse(jwtService.generateChatSocketToken(principal.getUsername()), 120);
    }
}
