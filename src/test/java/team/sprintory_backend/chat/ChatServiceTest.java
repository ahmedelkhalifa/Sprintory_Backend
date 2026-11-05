package team.sprintory_backend.chat;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import team.sprintory_backend.chat.events.ChatChanged;
import team.sprintory_backend.chat.models.ProjectChatMessage;
import team.sprintory_backend.chat.repositories.ProjectChatMessageRepository;
import team.sprintory_backend.chat.services.ChatContentSanitizer;
import team.sprintory_backend.chat.services.ChatService;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.project.models.*;
import team.sprintory_backend.project.services.*;
import team.sprintory_backend.user.models.User;

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
    private final ChatContentSanitizer sanitizer = mock(ChatContentSanitizer.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final User author = User.builder().id(8L).firstName("Ada").lastName("One").build();
    private final User other = User.builder().id(9L).firstName("Bob").lastName("Two").build();
    private final Project project = Project.builder().id(42L).build();
    private ChatService service;

    @BeforeEach void setup() {
        service = new ChatService(access, messages, sanitizer, mapper, events);
        var member = ProjectMember.builder().user(author).roles(Set.of(ProjectRole.MEMBER)).build();
        var manager = ProjectMember.builder().user(other).roles(Set.of(ProjectRole.PROJECT_MANAGER)).build();
        when(access.requireActive(42L, author, ProjectPermission.COMMENT))
                .thenReturn(new ProjectAccessResult(project, null, member, Set.of(ProjectPermission.COMMENT)));
        when(access.requireActive(42L, other, ProjectPermission.COMMENT))
                .thenReturn(new ProjectAccessResult(project, null, manager, Set.of(ProjectPermission.COMMENT)));
        when(access.require(42L, author, ProjectPermission.VIEW_WORK))
                .thenReturn(new ProjectAccessResult(project, null, member, Set.of(ProjectPermission.VIEW_WORK)));
    }

    private ProjectChatMessage existing() {
        var msg = new ProjectChatMessage();
        msg.setId(11L); msg.setProject(project); msg.setAuthor(author); msg.setAuthorName("Ada One");
        msg.setContentJson("{\"type\":\"doc\",\"content\":[]}"); msg.setCreatedAt(Instant.now());
        when(messages.findByIdAndProjectId(11L, 42L)).thenReturn(Optional.of(msg));
        return msg;
    }

    @Test void sendsOnlyAfterAuthorizationAndPublishesChange() throws Exception {
        var payload = mapper.readTree("{\"type\":\"doc\",\"content\":[]}");
        when(sanitizer.sanitize(42L, payload)).thenReturn(payload);
        when(messages.save(any())).thenAnswer(invocation -> {
            ProjectChatMessage msg = invocation.getArgument(0);
            msg.setId(11L);
            return msg;
        });
        var result = service.send(author, 42L, payload);
        assertEquals("Ada One", result.authorName());
        assertEquals(11L, result.id());
        verify(events).publishEvent((Object) argThat((ChatChanged e) -> "CREATED".equals(e.type())));
    }

    @Test void onlyOwnerOrManagerCanEditOrDelete() throws Exception {
        ProjectChatMessage msg = existing();
        var payload = mapper.readTree("{\"type\":\"doc\",\"content\":[]}");
        when(sanitizer.sanitize(42L, payload)).thenReturn(payload);
        User stranger = User.builder().id(10L).build();
        var strangerMember = ProjectMember.builder().user(stranger).roles(Set.of(ProjectRole.MEMBER)).build();
        when(access.requireActive(42L, stranger, ProjectPermission.COMMENT))
                .thenReturn(new ProjectAccessResult(project, null, strangerMember, Set.of(ProjectPermission.COMMENT)));
        assertThrows(AccessDeniedException.class, () -> service.edit(stranger, 42L, 11L, payload));
        var edited = service.edit(author, 42L, 11L, payload);
        assertNotNull(edited.editedAt());
        var deleted = service.delete(other, 42L, 11L);
        assertTrue(deleted.deleted());
        assertNull(deleted.content());
        assertNull(msg.getContentJson());
        verify(events, times(2)).publishEvent(any(ChatChanged.class));
    }

    @Test void historyReturnsOldestToNewestWithExclusiveCursor() {
        var data = new ArrayList<ProjectChatMessage>();
        for (long n : new long[]{13,12,11}) {
            var msg = existing();
            msg.setId(n);
            data.add(msg);
        }
        when(messages.findByProjectIdOrderByIdDesc(42L, PageRequest.of(0, 3))).thenReturn(data);
        var result = service.history(author, 42L, null, 2);
        assertEquals(List.of(12L, 13L), result.messages().stream().map(m -> m.id()).toList());
        assertEquals(12L, result.nextBefore());
        assertTrue(result.hasMore());
        assertThrows(BadRequestException.class, () -> service.history(author, 42L, -1L, 100));
    }

    @Test void workspaceAdminWithoutProjectMembershipIsNotAllowed() {
        var admin = User.builder().id(90L).build();
        when(access.require(42L, admin, ProjectPermission.VIEW_WORK))
                .thenReturn(new ProjectAccessResult(project, null, null, Set.of(ProjectPermission.VIEW_WORK)));
        assertThrows(AccessDeniedException.class, () -> service.history(admin, 42L, null, 50));
    }
}
