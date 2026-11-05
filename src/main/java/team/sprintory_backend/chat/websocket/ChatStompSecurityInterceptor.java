package team.sprintory_backend.chat.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.security.jwt.JwtService;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.security.userdetails.AppUserDetailsService;

import java.security.Principal;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Inbound authentication and authorization; outbound recheck blocks removed subscribers. */
@Component
@RequiredArgsConstructor
public class ChatStompSecurityInterceptor implements ChannelInterceptor {
    private static final Pattern TOPIC = Pattern.compile("^/topic/projects/([1-9][0-9]*)/chat$");
    private static final Pattern SEND = Pattern.compile("^/app/projects/([1-9][0-9]*)/chat\\.(send|edit|delete)$");

    private final JwtService jwtService;
    private final AppUserDetailsService userDetailsService;
    private final ProjectMemberRepository members;
    private final ProjectAccess projectAccess;
    // Broker outbound MESSAGE frames have a session id but usually no simpUser. Only CONNECT
    // establishes this mapping, using a verified JWT. Never use a client-supplied user id.
    private final ConcurrentMap<String, Long> sessionUsers = new ConcurrentHashMap<>();

    public Message<?> inbound(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor stomp = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (stomp == null) throw new AccessDeniedException("Invalid STOMP frame.");
        StompCommand command = stomp.getCommand();
        if (command == null) return message;
        if (command == StompCommand.CONNECT) {
            String authorization = first(stomp.getNativeHeader("Authorization"));
            if (authorization == null || !authorization.startsWith("Bearer ")
                    || !jwtService.isValidChatSocketToken(authorization.substring(7))) {
                throw new AccessDeniedException("Valid chat STOMP token required.");
            }
            String email = jwtService.extractSubject(authorization.substring(7));
            AppUserDetails details = (AppUserDetails) userDetailsService.loadUserByUsername(email);
            if (!details.isEnabled()) throw new AccessDeniedException("Account disabled.");
            if (stomp.getSessionId() == null) throw new AccessDeniedException("Missing socket session id.");
            stomp.setUser(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
            sessionUsers.put(stomp.getSessionId(), details.getUser().getId());
            return message;
        }
        if (command == StompCommand.DISCONNECT) {
            if (stomp.getSessionId() != null) sessionUsers.remove(stomp.getSessionId());
            return message;
        }
        if (command == StompCommand.SUBSCRIBE) {
            AppUserDetails user = requireUser(stomp.getUser());
            Long projectId = matchProject(TOPIC, stomp.getDestination());
            if (!members.existsByProjectIdAndUserId(projectId, user.getUser().getId())) {
                throw new AccessDeniedException("Project membership required to subscribe.");
            }
        } else if (command == StompCommand.SEND) {
            AppUserDetails user = requireUser(stomp.getUser());
            Long projectId = matchProject(SEND, stomp.getDestination());
            // Never trust a socket destination or incoming author id. Check again in ChatService.
            var access = projectAccess.requireActive(projectId, user.getUser(), ProjectPermission.COMMENT);
            if (access.projectMember() == null) throw new AccessDeniedException("Project membership required.");
        }
        return message;
    }

    public Message<?> outbound(Message<?> message, MessageChannel channel) {
        SimpMessageHeaderAccessor stomp = SimpMessageHeaderAccessor.wrap(message);
        if (stomp.getMessageType() != SimpMessageType.MESSAGE) return message;
        Matcher matcher = TOPIC.matcher(stomp.getDestination() == null ? "" : stomp.getDestination());
        if (!matcher.matches()) return null; // No other outbound topic permitted for this endpoint.
        Long userId = sessionUsers.get(stomp.getSessionId());
        if (userId == null) return null;
        // This check runs for EACH outbound delivery, not only at subscription time. A removed
        // member receives no messages from the next committed membership change onward.
        return members.existsByProjectIdAndUserId(Long.valueOf(matcher.group(1)), userId)
                ? message : null;
    }

    private static AppUserDetails requireUser(Principal principal) {
        if (principal instanceof org.springframework.security.core.Authentication authentication
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AppUserDetails details) return details;
        throw new AccessDeniedException("Authenticated chat socket required.");
    }

    private static Long matchProject(Pattern allowed, String destination) {
        Matcher matcher = allowed.matcher(destination == null ? "" : destination);
        if (!matcher.matches()) throw new AccessDeniedException("Unsupported chat destination.");
        try {
            return Long.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            throw new AccessDeniedException("Invalid project id.");
        }
    }

    private static String first(List<String> headers) {
        return headers == null || headers.isEmpty() ? null : headers.get(0);
    }
}
