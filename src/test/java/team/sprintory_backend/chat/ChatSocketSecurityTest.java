package team.sprintory_backend.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import team.sprintory_backend.chat.websocket.ChatStompSecurityInterceptor;
import team.sprintory_backend.project.models.*;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.project.services.*;
import team.sprintory_backend.security.jwt.JwtService;
import team.sprintory_backend.security.userdetails.*;
import team.sprintory_backend.user.models.User;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatSocketSecurityTest {
    private final JwtService jwt = new JwtService("0123456789abcdef0123456789abcdef", 900000);
    private final AppUserDetailsService userDetails = mock(AppUserDetailsService.class);
    private final ProjectMemberRepository members = mock(ProjectMemberRepository.class);
    private final ProjectAccess access = mock(ProjectAccess.class);
    private final User user = User.builder().id(1L).email("ada@example.com").enabled(true).build();
    private final AppUserDetails details = new AppUserDetails(user);
    private final UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
    private ChatStompSecurityInterceptor interceptor;

    @BeforeEach void setup() {
        interceptor = new ChatStompSecurityInterceptor(jwt, userDetails, members, access);
    }

    @Test void connectsWithScopedJwtNotRegularAccessJwt() {
        when(userDetails.loadUserByUsername("ada@example.com")).thenReturn(details);
        var invalid = StompHeaderAccessor.create(StompCommand.CONNECT);
        invalid.addNativeHeader("Authorization", "Bearer " + jwt.generateAccessToken("ada@example.com"));
        invalid.setLeaveMutable(true);
        assertThrows(AccessDeniedException.class, () -> interceptor.inbound(frame(invalid), null));
        var valid = StompHeaderAccessor.create(StompCommand.CONNECT);
        valid.addNativeHeader("Authorization", "Bearer " + jwt.generateChatSocketToken("ada@example.com"));
        valid.setSessionId("test-session");
        valid.setLeaveMutable(true);
        interceptor.inbound(frame(valid), null);
        assertNotNull(valid.getUser());
        assertFalse(jwt.isValid(jwt.generateChatSocketToken("ada@example.com")));
    }

    @Test void outsiderCannotSubscribeEvenWithValidConnection() {
        var sub = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        sub.setDestination("/topic/projects/42/chat"); sub.setUser(auth);
        assertThrows(AccessDeniedException.class, () -> interceptor.inbound(frame(sub), null));
        when(members.existsByProjectIdAndUserId(42L, 1L)).thenReturn(true);
        assertNotNull(interceptor.inbound(frame(sub), null));
        var forbidden = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        forbidden.setDestination("/topic/projects/42/private"); forbidden.setUser(auth);
        assertThrows(AccessDeniedException.class, () -> interceptor.inbound(frame(forbidden), null));
    }

    @Test void viewerCannotSendAndClientCannotPublishDirectlyToTopic() {
        var send = StompHeaderAccessor.create(StompCommand.SEND);
        send.setUser(auth); send.setDestination("/app/projects/42/chat.send");
        when(access.requireActive(42L, user, ProjectPermission.COMMENT))
                .thenThrow(new AccessDeniedException("Viewer"));
        assertThrows(AccessDeniedException.class, () -> interceptor.inbound(frame(send), null));
        var topicSend = StompHeaderAccessor.create(StompCommand.SEND);
        topicSend.setUser(auth); topicSend.setDestination("/topic/projects/42/chat");
        assertThrows(AccessDeniedException.class, () -> interceptor.inbound(frame(topicSend), null));
    }

    @Test void removedMemberStopsReceivingWithoutReconnecting() {
        when(userDetails.loadUserByUsername("ada@example.com")).thenReturn(details);
        var connect = StompHeaderAccessor.create(StompCommand.CONNECT);
        connect.setSessionId("session-1");
        connect.addNativeHeader("Authorization", "Bearer " + jwt.generateChatSocketToken("ada@example.com"));
        connect.setLeaveMutable(true);
        interceptor.inbound(frame(connect), null);
        var outgoing = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        outgoing.setSessionId("session-1");
        outgoing.setDestination("/topic/projects/42/chat");
        Message<?> frame = MessageBuilder.createMessage(new byte[0], outgoing.getMessageHeaders());
        when(members.existsByProjectIdAndUserId(42L, 1L)).thenReturn(true, false);
        assertNotNull(interceptor.outbound(frame, null));
        assertNull(interceptor.outbound(frame, null));
    }

    private static Message<?> frame(StompHeaderAccessor headers) {
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
