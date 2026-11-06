package team.sprintory_backend.chat.services;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.audit.models.AuditTarget;
import team.sprintory_backend.chat.dto.ChatHistoryResponse;
import team.sprintory_backend.chat.dto.ChatMessageResponse;
import team.sprintory_backend.chat.events.ChatChanged;
import team.sprintory_backend.chat.events.ProjectChatMentioned;
import team.sprintory_backend.chat.models.ProjectChatMessage;
import team.sprintory_backend.chat.repositories.ProjectChatMessageRepository;
import team.sprintory_backend.common.richtext.InvalidRichTextException;
import team.sprintory_backend.common.richtext.RichTextDocument;
import team.sprintory_backend.common.richtext.RichTextMention;
import team.sprintory_backend.common.richtext.RichTextParser;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.mention.services.MentionService;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.project.services.ProjectAccessResult;
import team.sprintory_backend.team.models.Team;
import team.sprintory_backend.team.repositories.TeamRepository;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One project-scoped chat, sharing the same rich-text and mentions rules as task comments. */
@Service
@RequiredArgsConstructor
public class ChatService {
    public static final int MAX_LENGTH = 10_000;

    private final ProjectAccess access;
    private final ProjectChatMessageRepository repository;
    private final RichTextParser richText;
    private final MentionService mentions;
    private final ObjectMapper mapper;
    private final UserRepository users;
    private final TeamRepository teams;
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
        List<ProjectChatMessage> visible = newest.stream().limit(limit).toList();
        Map<RichTextMention, String> names = currentNames(visible);
        List<ChatMessageResponse> messages = new ArrayList<>();
        visible.forEach(msg -> messages.add(toResponse(msg, names)));
        Collections.reverse(messages);
        Long nextBefore = hasMore ? messages.get(0).id() : null;
        return new ChatHistoryResponse(messages, nextBefore, hasMore);
    }

    @Transactional
    public ChatMessageResponse send(User caller, Long projectId, JsonNode content) {
        ProjectAccessResult result = requireWriter(caller, projectId);
        RichTextDocument body = parseBody(content);
        Set<Long> mentioned = mentions.newlyMentionedUserIds(result.project(), body, null);
        ProjectChatMessage msg = new ProjectChatMessage();
        msg.setProject(result.project());
        msg.setAuthor(caller);
        msg.setAuthorName(AuditTarget.displayName(caller));
        msg.setContentJson(body.json());
        msg.setCreatedAt(Instant.now());
        ProjectChatMessage saved = repository.save(msg);
        ChatMessageResponse response = toResponse(saved);
        events.publishEvent(new ChatChanged("CREATED", projectId, response));
        publishMentions(result, caller, saved.getId(), mentioned, body);
        return response;
    }

    @Transactional
    public ChatMessageResponse edit(User caller, Long projectId, Long messageId, JsonNode content) {
        ProjectAccessResult result = requireWriter(caller, projectId);
        ProjectChatMessage msg = requireMessage(projectId, messageId);
        if (msg.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Chat message not found.");
        }
        requireAuthor(caller, msg);
        RichTextDocument body = parseBody(content);
        if (!body.json().equals(msg.getContentJson())) {
            RichTextDocument previous = richText.parse(msg.getContentJson());
            Set<Long> mentioned = mentions.newlyMentionedUserIds(result.project(), body, previous);
            msg.setContentJson(body.json());
            msg.setEditedAt(Instant.now());
            ChatMessageResponse response = toResponse(msg);
            events.publishEvent(new ChatChanged("EDITED", projectId, response));
            publishMentions(result, caller, msg.getId(), mentioned, body);
            return response;
        }
        return toResponse(msg);
    }

    @Transactional
    public ChatMessageResponse delete(User caller, Long projectId, Long messageId) {
        ProjectAccessResult result = requireWriter(caller, projectId);
        ProjectChatMessage msg = requireMessage(projectId, messageId);
        if (msg.getDeletedAt() != null) return toResponse(msg);
        requireAuthorOrModerator(caller, result, msg);
        msg.setDeletedAt(Instant.now());
        msg.setContentJson(null);
        ChatMessageResponse response = toResponse(msg);
        events.publishEvent(new ChatChanged("DELETED", projectId, response));
        return response;
    }

    /** The notifications module will listen to this project-scoped event (not task-scoped Mentioned). */
    private void publishMentions(ProjectAccessResult result, User caller, Long messageId,
                                 Set<Long> mentioned, RichTextDocument body) {
        if (!mentioned.isEmpty()) {
            events.publishEvent(new ProjectChatMentioned(result.project(), caller, messageId, mentioned,
                    body.plainText()));
        }
    }

    private RichTextDocument parseBody(JsonNode content) {
        if (content == null) throw new InvalidRichTextException("The text is not a valid document.");
        RichTextDocument body = richText.parse(content.toString());
        if (body.isEmpty()) throw new InvalidRichTextException("A chat message cannot be empty.");
        if (body.plainText().length() > MAX_LENGTH) {
            throw new InvalidRichTextException("A chat message can have at most " + MAX_LENGTH + " characters.");
        }
        return body;
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

    private static boolean isAuthor(User caller, ProjectChatMessage msg) {
        return msg.getAuthor() != null && caller.getId().equals(msg.getAuthor().getId());
    }

    private static void requireAuthor(User caller, ProjectChatMessage msg) {
        if (!isAuthor(caller, msg)) {
            throw new AccessDeniedException("Only the author may edit this message.");
        }
    }

    private static void requireAuthorOrModerator(User caller, ProjectAccessResult result, ProjectChatMessage msg) {
        if (!isAuthor(caller, msg) && !result.can(ProjectPermission.MODERATE_COMMENTS)) {
            throw new AccessDeniedException("Only the author or a moderator may delete this message.");
        }
    }

    private ChatMessageResponse toResponse(ProjectChatMessage msg) {
        return toResponse(msg, currentNames(List.of(msg)));
    }

    private ChatMessageResponse toResponse(ProjectChatMessage msg, Map<RichTextMention, String> names) {
        JsonNode content = null;
        List<ChatMessageResponse.MentionName> mentionNames = new ArrayList<>();
        if (msg.getContentJson() != null) {
            RichTextDocument body = richText.parse(msg.getContentJson());
            content = mapper.readTree(body.json());
            for (RichTextMention mention : body.mentions()) {
                mentionNames.add(new ChatMessageResponse.MentionName(
                        mention.kind().json(), mention.id(), names.get(mention)));
            }
        }
        return new ChatMessageResponse(msg.getId(), msg.getProject().getId(),
                msg.getAuthor() == null ? null : msg.getAuthor().getId(), msg.getAuthorName(),
                content, msg.getCreatedAt(), msg.getEditedAt(), msg.getDeletedAt() != null, mentionNames);
    }

    /** Resolve names in two batched queries per history page, not one lookup per message. */
    private Map<RichTextMention, String> currentNames(List<ProjectChatMessage> messages) {
        Set<Long> userIds = new HashSet<>();
        Set<Long> teamIds = new HashSet<>();
        for (ProjectChatMessage msg : messages) {
            if (msg.getContentJson() == null) continue;
            RichTextDocument body = richText.parse(msg.getContentJson());
            userIds.addAll(body.userIds());
            teamIds.addAll(body.teamIds());
        }
        Map<RichTextMention, String> names = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (User user : users.findAllById(userIds)) {
                names.put(RichTextMention.user(user.getId()), AuditTarget.displayName(user));
            }
        }
        if (!teamIds.isEmpty()) {
            for (Team team : teams.findAllById(teamIds)) {
                names.put(RichTextMention.team(team.getId()), team.getName());
            }
        }
        return names;
    }
}
