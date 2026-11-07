package team.sprintory_backend;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import team.sprintory_backend.chat.dto.ChatMessageRequest;
import team.sprintory_backend.chat.events.ChatChanged;
import team.sprintory_backend.exceptionHandler.ErrorCode;
import team.sprintory_backend.exceptionHandler.ErrorResponse;
import team.sprintory_backend.project.dto.CreateProjectRequest;
import team.sprintory_backend.project.models.ProjectMember;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectService;
import team.sprintory_backend.security.jwt.JwtService;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;
import team.sprintory_backend.workspace.repositories.WorkspaceRepository;

import java.lang.reflect.Type;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** A real, running Spring Boot WebSocket and disposable PostgreSQL: no frontend is needed. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class ChatStompIntegrationTest {
    @Value("${local.server.port}") private int port;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository workspaceMembers;
    @Autowired private ProjectMemberRepository projectMembers;
    @Autowired private ProjectRepository projects;
    @Autowired private ProjectService projectService;
    @Autowired private JwtService jwt;
    @Autowired private ObjectMapper mapper;

    @Test void twoStompClientsExchangeEventsAndRemovedMemberStopsReceiving() throws Exception {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        User owner = users.save(User.builder().firstName("Owner").lastName("Test")
                .email("chat-owner-" + marker + "@example.com").enabled(true).build());
        User friend = users.save(User.builder().firstName("Friend").lastName("Test")
                .email("chat-friend-" + marker + "@example.com").enabled(true).build());
        Workspace workspace = workspaces.save(Workspace.builder().name("Chat test").slug("chat-" + marker).build());
        for (User u : new User[]{owner, friend}) {
            workspaceMembers.save(WorkspaceMember.builder().workspace(workspace).user(u)
                    .role(Role.WORKSPACE_MEMBER).build());
        }
        Long projectId = projectService.create(owner, workspace.getId(),
                new CreateProjectRequest("Project", "CHT", ProjectTemplate.KANBAN, null)).id();
        projectMembers.save(ProjectMember.builder().project(projects.getReferenceById(projectId))
                .user(friend).roles(Set.of(ProjectRole.MEMBER)).build());

        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        client.start();
        StompSession sender = null;
        StompSession receiver = null;
        try {
            sender = connect(client, owner);
            receiver = connect(client, friend);
            String topic = "/topic/projects/" + projectId + "/chat";
            LinkedBlockingQueue<ChatChanged> received = new LinkedBlockingQueue<>();
            receiver.subscribe(topic, new StompFrameHandler() {
                @Override public Type getPayloadType(StompHeaders headers) { return ChatChanged.class; }
                @Override public void handleFrame(StompHeaders headers, Object payload) {
                    received.add((ChatChanged) payload);
                }
            });
            // Spring's simple STOMP broker has no subscription receipts.
            Thread.sleep(350);

            var doc = mapper.readTree("""
                {"type":"doc","content":[{"type":"paragraph","content":[
                {"type":"text","text":"Hello from client A"}]}]}
                """);
            sender.send("/app/projects/" + projectId + "/chat.send", new ChatMessageRequest(doc));
            ChatChanged first = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(first, "Second WebSocket client did not receive message");
            assertEquals("CREATED", first.type());
            assertEquals(owner.getId(), first.message().authorId());

            projectService.removeMember(owner, projectId, friend.getId());
            sender.send("/app/projects/" + projectId + "/chat.send", new ChatMessageRequest(doc));
            assertNull(received.poll(1200, TimeUnit.MILLISECONDS),
                    "Removed subscriber received a project message");
        } finally {
            if (sender != null) sender.disconnect();
            if (receiver != null) receiver.disconnect();
            client.stop();
        }
    }

    @Test void aRefusedSendIsReportedToTheSenderOnly() throws Exception {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        User owner = users.save(User.builder().firstName("Owner").lastName("Test")
                .email("chat-err-owner-" + marker + "@example.com").enabled(true).build());
        User friend = users.save(User.builder().firstName("Friend").lastName("Test")
                .email("chat-err-friend-" + marker + "@example.com").enabled(true).build());
        Workspace workspace = workspaces.save(Workspace.builder().name("Chat errors").slug("chat-err-" + marker).build());
        for (User u : new User[]{owner, friend}) {
            workspaceMembers.save(WorkspaceMember.builder().workspace(workspace).user(u)
                    .role(Role.WORKSPACE_MEMBER).build());
        }
        Long projectId = projectService.create(owner, workspace.getId(),
                new CreateProjectRequest("Project", "CHE", ProjectTemplate.KANBAN, null)).id();
        projectMembers.save(ProjectMember.builder().project(projects.getReferenceById(projectId))
                .user(friend).roles(Set.of(ProjectRole.MEMBER)).build());

        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        client.start();
        StompSession sender = null;
        StompSession receiver = null;
        try {
            sender = connect(client, owner);
            receiver = connect(client, friend);
            LinkedBlockingQueue<ErrorResponse> senderErrors = errorsOf(sender);
            LinkedBlockingQueue<ErrorResponse> receiverErrors = errorsOf(receiver);
            Thread.sleep(350); // Spring's simple STOMP broker has no subscription receipts.

            var unknownNode = mapper.readTree("""
                {"type":"doc","content":[{"type":"script","content":[]}]}
                """);
            sender.send("/app/projects/" + projectId + "/chat.send", new ChatMessageRequest(unknownNode));

            ErrorResponse error = senderErrors.poll(5, TimeUnit.SECONDS);
            assertNotNull(error, "The sender got no error frame");
            assertEquals(ErrorCode.INVALID_RICH_TEXT, error.getCode());
            assertEquals(400, error.getStatus());
            assertEquals("/app/projects/" + projectId + "/chat.send", error.getPath());
            assertNull(receiverErrors.poll(1200, TimeUnit.MILLISECONDS), "Another client saw the sender's error");
            assertTrue(sender.isConnected(), "A refused message must not close the connection");
        } finally {
            if (sender != null) sender.disconnect();
            if (receiver != null) receiver.disconnect();
            client.stop();
        }
    }

    private static LinkedBlockingQueue<ErrorResponse> errorsOf(StompSession session) {
        LinkedBlockingQueue<ErrorResponse> errors = new LinkedBlockingQueue<>();
        session.subscribe("/user/queue/errors", new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return ErrorResponse.class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) {
                errors.add((ErrorResponse) payload);
            }
        });
        return errors;
    }

    private StompSession connect(WebSocketStompClient client, User user) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + jwt.generateChatSocketToken(user.getEmail()));
        return client.connectAsync("ws://localhost:" + port + "/ws/chat", new org.springframework.web.socket.WebSocketHttpHeaders(),
                headers, new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);
    }
}
