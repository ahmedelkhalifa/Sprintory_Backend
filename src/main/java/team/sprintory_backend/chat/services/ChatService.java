package team.sprintory_backend.chat.services;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.chat.dto.ChatHistoryResponse;
import team.sprintory_backend.chat.dto.ChatMessageResponse;
import team.sprintory_backend.chat.events.ChatChanged;
import team.sprintory_backend.chat.models.ProjectChatMessage;
import team.sprintory_backend.chat.repositories.ProjectChatMessageRepository;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.project.services.ProjectAccessResult;
import team.sprintory_backend.user.models.User;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatService {
    private final ProjectAccess access;
    private final ProjectChatMessageRepository repository;
    private final ChatContentSanitizer contentSanitizer;
    private final ObjectMapper mapper;
    private final ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public ChatHistoryResponse history(User caller, Long projectId, Long before, int limit) {
        requireMember(caller, projectId);
        if (limit < 1 || limit > 100 || (before != null && before < 1)) {
            throw new BadRequestException("limit must be 1..100 and before must be positive.");
        }
        var page = PageRequest.of(0, limit + 1);
        List<ProjectChatMessage> newest = before == null
                ? repository.findByProjectIdOrderByIdDesc(projectId, page)
                : repository.findByProjectIdAndIdLessThanOrderByIdDesc(projectId, before, page);
        boolean hasMore = newest.size() > limit;
        List<ChatMessageResponse> messages = new ArrayList<>();
        newest.stream().limit(limit).forEach(msg -> messages.add(toResponse(msg)));
        Collections.reverse(messages);
        Long nextBefore = hasMore ? messages.get(0).id() : null;
        return new ChatHistoryResponse(messages, nextBefore, hasMore);
    }

    @Transactional
    public ChatMessageResponse send(User caller, Long projectId, JsonNode content) {
        ProjectAccessResult result = requireWriter(caller, projectId);
        JsonNode safe = contentSanitizer.sanitize(projectId, content);
        ProjectChatMessage msg = new ProjectChatMessage();
        msg.setProject(result.project());
        msg.setAuthor(caller);
        msg.setAuthorName(caller.getFirstName() + " " + caller.getLastName());
        msg.setContentJson(safe.toString());
        msg.setCreatedAt(Instant.now());
        ProjectChatMessage saved = repository.save(msg);
        ChatMessageResponse response = toResponse(saved);
        events.publishEvent(new ChatChanged("CREATED", projectId, response));
        return response;
    }

    @Transactional
    public ChatMessageResponse edit(User caller, Long projectId, Long messageId, JsonNode content) {
        ProjectAccessResult result = requireWriter(caller, projectId);
        ProjectChatMessage msg = requireMessage(projectId, messageId);
        if (msg.getDeletedAt() != null) throw new BadRequestException("Message was deleted.");
        requireAuthorOrManager(caller, result, msg);
        JsonNode safe = contentSanitizer.sanitize(projectId, content);
        msg.setContentJson(safe.toString());
        msg.setEditedAt(Instant.now());
        ChatMessageResponse response = toResponse(msg);
        events.publishEvent(new ChatChanged("EDITED", projectId, response));
        return response;
    }

    @Transactional
    public ChatMessageResponse delete(User caller, Long projectId, Long messageId) {
        ProjectAccessResult result = requireWriter(caller, projectId);
        ProjectChatMessage msg = requireMessage(projectId, messageId);
        if (msg.getDeletedAt() != null) return toResponse(msg);
        requireAuthorOrManager(caller, result, msg);
        msg.setDeletedAt(Instant.now());
        msg.setContentJson(null);
        ChatMessageResponse response = toResponse(msg);
        events.publishEvent(new ChatChanged("DELETED", projectId, response));
        return response;
    }

    /** Workspace admins without project membership cannot read or write project chat. */
    private ProjectAccessResult requireMember(User caller, Long projectId) {
        ProjectAccessResult result = access.require(projectId, caller, ProjectPermission.VIEW_WORK);
        if (result.projectMember() == null) throw new AccessDeniedException("Project membership required.");
        return result;
    }

    private ProjectAccessResult requireWriter(User caller, Long projectId) {
        ProjectAccessResult result = access.requireActive(projectId, caller, ProjectPermission.COMMENT);
        if (result.projectMember() == null) throw new AccessDeniedException("Project membership required.");
        return result;
    }

    private ProjectChatMessage requireMessage(Long projectId, Long messageId) {
        return repository.findByIdAndProjectId(messageId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat message not found."));
    }

    private static void requireAuthorOrManager(User caller, ProjectAccessResult result, ProjectChatMessage msg) {
        boolean own = msg.getAuthor() != null && caller.getId().equals(msg.getAuthor().getId());
        boolean manager = result.projectMember().getRoles().contains(ProjectRole.PROJECT_MANAGER);
        if (!own && !manager) throw new AccessDeniedException("Only the author or project manager may modify this message.");
    }

    private ChatMessageResponse toResponse(ProjectChatMessage msg) {
        JsonNode content = null;
        if (msg.getContentJson() != null) {
            try {
                content = mapper.readTree(msg.getContentJson());
            } catch (JacksonException e) {
                throw new IllegalStateException("Stored chat JSON is malformed.", e);
            }
        }
        return new ChatMessageResponse(msg.getId(), msg.getProject().getId(),
                msg.getAuthor() == null ? null : msg.getAuthor().getId(), msg.getAuthorName(),
                content, msg.getCreatedAt(), msg.getEditedAt(), msg.getDeletedAt() != null);
    }
}
