package team.sprintory_backend.security.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import team.sprintory_backend.exceptionHandler.ErrorCode;
import team.sprintory_backend.exceptionHandler.ErrorResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class AppAccessDeniedHandler implements AccessDeniedHandler {
    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean unauthenticated =
                auth == null || auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated();

        HttpStatus status = unauthenticated ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
        String message = unauthenticated
                ? "Authentication required. Please log in."
                : "Forbidden, You don't have access to this resource";

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ErrorResponse.builder()
                .message(message)
                .status(status)
                .path(request.getRequestURI())
                .timestamp(Instant.now())
                .errorCode(ErrorCode.AUTHENTICATION_ERROR)
                .build());
    }
}
