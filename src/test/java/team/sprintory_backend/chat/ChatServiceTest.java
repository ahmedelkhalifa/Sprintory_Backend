package team.sprintory_backend.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import team.sprintory_backend.chat.events.ChatChanged;
import team.sprintory_backend.chat.events.ProjectChatMentioned;
import team.sprintory_backend.chat.models.ProjectChatMessage;
import team.sprintory_backend.chat.repositories.ProjectChatMessageRepository;
import team.sprintory_backend.chat.services.ChatService;
import team.sprintory_backend.common.richtext.InvalidRichTextException;
import team.sprintory_backend.common.richtext.RichTextDocument;
import team.sprintory_backend.common.richtext.RichTextParser;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.mention.exceptions.InvalidMentionException;
import team.sprintory_backend.mention.services.MentionService;
import team.sprintory_backend.project.models.*;
import team.sprintory_backend.project.services.*;
import team.sprintory_backend.team.repositories.TeamRepository;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatServiceTest {
    private final ProjectAccess access = mock(ProjectAccess.class);
    private final ProjectChatMessageRepository messages = mock(ProjectChatMessageRepository.class);
    private final MentionService mentions = mock(MentionService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RichTextParser richText = new RichTextParser(mapper);
    private final User author = User.builder().id(8L).firstName("Ada").lastName("One").build();
    private final User manager = User.builder().id(9L).firstName("Bob").lastName("Two").build();
    private final Project project = Project.builder().id(42L).build();
    private ChatService service;

    private static final String HELLO = """
            {"type":"doc","content":[{"type":"paragraph","content":[
              {"type":"text","text":"Hello"}]}]}
            """;
    private static final String MENTION = """
            {"type":"doc","content":[{"type":"paragraph","content":[
              {"type":"text","text":"Hey "},
              {"type":"mention","attrs":{"kind":"team","id":"5","label":"Backend"}}
            ]}]}
            """;

    @BeforeEach void setup() {
        service = new ChatService(access, messages, richText, mentions, mapper, users, teams, events);
        var member = ProjectMember.builder().user(author).roles(Set.of(ProjectRole.MEMBER)).build();
        var pm = ProjectMember.builder().user(manager).roles(Set.of(ProjectRole.PROJECT_MANAGER)).build();
        when(access.requireActive(42L, author, ProjectPermission.COMMENT))
                .thenReturn(new ProjectAccessResult(project, null, member, Set.of(ProjectPermission.COMMENT)));
        when(access.requireActive(42L, manager, ProjectPermission.COMMENT))
                .thenReturn(new ProjectAccessResult(project, null, pm,
                        Set.of(ProjectPermission.COMMENT, ProjectPermission.MODERATE_COMMENTS)));
        when(access.require(42L, author, ProjectPermission.VIEW_WORK))
                .thenReturn(new ProjectAccessResult(project, null, member, Set.of(ProjectPermission.VIEW_WORK)));
        when(users.findAllById(any())).thenReturn(List.of());
        when(teams.findAllById(any())).thenReturn(List.of());
        when(messages.save(any())).thenAnswer(inv -> {
            ProjectChatMessage msg = inv.getArgument(0);
            if (msg.getId() == null) msg.setId(11L);
            return msg;
        });
    }

    private JsonNode doc(String json) {
        return mapper.readTree(json);
    }

    private ProjectChatMessage existing(String json) {
        var msg = new ProjectChatMessage();
        msg.setId(11L);
        msg.setProject(project);
        msg.setAuthor(author);
        msg.setAuthorName("Ada One");
        msg.setContentJson(json);
        msg.setCreatedAt(Instant.now());
        when(messages.findByIdAndProjectId(11L, 42L)).thenReturn(Optional.of(msg));
        return msg;
    }

    @Test void sendsAndPublishesChangedEventAfterValidation() {
        var response = service.send(author, 42L, doc(HELLO));
        assertEquals(11L, response.id());
        assertEquals("Ada One", response.authorName());
        assertTrue(response.mentions().isEmpty());
        verify(mentions).newlyMentionedUserIds(eq(project), any(RichTextDocument.class), isNull());
        verify(events).publishEvent(any(ChatChanged.class));
        verify(events, never()).publishEvent(any(ProjectChatMentioned.class));
    }

    @Test void teamMentionIsAcceptedAndProducesProjectScopedNotificationEvent() {
        when(mentions.newlyMentionedUserIds(eq(project), any(), isNull())).thenReturn(Set.of(15L, 16L));
        var response = service.send(author, 42L, doc(MENTION));
        assertEquals(1, response.mentions().size());
        assertEquals("team", response.mentions().get(0).kind());
        assertEquals(5L, response.mentions().get(0).id());
        var evt = org.mockito.ArgumentCaptor.forClass(ProjectChatMentioned.class);
        verify(events).publishEvent(evt.capture());
        assertEquals(42L, evt.getValue().project().getId());
        assertEquals(11L, evt.getValue().messageId());
        assertEquals(Set.of(15L, 16L), evt.getValue().userIds());
    }

    @Test void editNotifiesOnlyNewMentionsAndUnchangedTextDoesNotNotifyAgain() {
        ProjectChatMessage old = existing(HELLO);
        when(mentions.newlyMentionedUserIds(eq(project), any(), any())).thenReturn(Set.of(15L));
        var response = service.edit(author, 42L, 11L, doc(MENTION));
        assertNotNull(response.editedAt());
        assertTrue(old.getContentJson().contains("\"kind\":\"team\""));
        verify(events).publishEvent(any(ProjectChatMentioned.class));
        clearInvocations(events, mentions);
        service.edit(author, 42L, 11L, doc(MENTION));
        verifyNoInteractions(mentions, events);
    }

    @Test void onlyAuthorEditsAndModeratorMayOnlyDelete() {
        var msg = existing(HELLO);
        assertThrows(AccessDeniedException.class, () -> service.edit(manager, 42L, 11L, doc(HELLO)));
        var deleted = service.delete(manager, 42L, 11L);
        assertTrue(deleted.deleted());
        assertNull(deleted.content());
        assertNull(msg.getContentJson());
        verify(events).publishEvent(any(ChatChanged.class));
    }

    @Test void deletedMessageEditIsNotFound() {
        var msg = existing(HELLO);
        msg.setDeletedAt(Instant.now());
        assertThrows(ResourceNotFoundException.class, () -> service.edit(author, 42L, 11L, doc(HELLO)));
    }

    @Test void invalidMentionIsRejectedBeforeSaving() {
        when(mentions.newlyMentionedUserIds(eq(project), any(), isNull()))
                .thenThrow(new InvalidMentionException("Only members of this project can be mentioned."));
        assertThrows(InvalidMentionException.class, () -> service.send(author, 42L, doc(MENTION)));
        verify(messages, never()).save(any());
    }

    @Test void sharedParserNormalizesRealEditorAttributesAndRejectsUnsafeNodes() {
        var input = doc("""
            {"type":"doc","content":[
              {"type":"orderedList","attrs":{"start":2,"class":"drop"},
                "content":[{"type":"listItem","content":[{"type":"paragraph","content":[
                  {"type":"text","text":"number two"}]}]}]},
              {"type":"codeBlock","attrs":{"language":"java","class":"drop"},
                "content":[{"type":"text","text":"System.out.println(1);"}]},
              {"type":"paragraph","content":[{"type":"text","text":"link","marks":[
                {"type":"link","attrs":{"href":"https://example.com","target":"_blank",
                  "rel":"noopener","class":"drop"}}]}]}
            ]}
            """);
        var msg = service.send(author, 42L, input);
        var children = msg.content().get("content");
        var orderedAttrs = children.get(0).get("attrs");
        assertEquals(2, orderedAttrs.get("start").intValue());
        assertFalse(orderedAttrs.has("class"));
        assertEquals("java", children.get(1).get("attrs").get("language").stringValue());
        var linkAttrs = children.get(2).get("content").get(0).get("marks").get(0).get("attrs");
        assertEquals("https://example.com", linkAttrs.get("href").stringValue());
        assertFalse(linkAttrs.has("target"));
        assertFalse(linkAttrs.has("rel"));
        assertFalse(linkAttrs.has("class"));
        assertThrows(InvalidRichTextException.class, () -> service.send(author, 42L,
                doc("""
                    {"type":"doc","content":[{"type":"image","attrs":{"src":"x"}}]}
                    """)));
    }

    @Test void historyReturnsOldestToNewestWithExclusiveCursor() {
        var data = new ArrayList<ProjectChatMessage>();
        for (long n : new long[]{13, 12, 11}) {
            var msg = new ProjectChatMessage();
            msg.setId(n); msg.setProject(project); msg.setAuthor(author);
            msg.setContentJson(HELLO); msg.setCreatedAt(Instant.now());
            data.add(msg);
        }
        when(messages.findByProjectIdOrderByIdDesc(42L, PageRequest.of(0, 3))).thenReturn(data);
        var result = service.history(author, 42L, null, 2);
        assertEquals(List.of(12L, 13L), result.messages().stream().map(m -> m.id()).toList());
        assertEquals(12L, result.nextBefore());
        assertTrue(result.hasMore());
        assertThrows(BadRequestException.class, () -> service.history(author, 42L, -1L, 100));
    }

    @Test void workspaceAdminWithoutMembershipIsDenied() {
        var admin = User.builder().id(90L).build();
        when(access.require(42L, admin, ProjectPermission.VIEW_WORK))
                .thenReturn(new ProjectAccessResult(project, null, null, Set.of(ProjectPermission.VIEW_WORK)));
        assertThrows(AccessDeniedException.class, () -> service.history(admin, 42L, null, 50));
    }
}
