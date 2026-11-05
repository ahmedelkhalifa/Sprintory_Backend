package team.sprintory_backend.chat;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import team.sprintory_backend.chat.services.ChatContentSanitizer;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatContentSanitizerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProjectMemberRepository members = mock(ProjectMemberRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private ChatContentSanitizer sanitizer;

    @BeforeEach void setup() {
        sanitizer = new ChatContentSanitizer(mapper, members, users);
    }

    @Test void acceptsTextAsDataWithoutExecutingHtml() throws Exception {
        var content = mapper.readTree("""
            {"type":"doc","content":[{"type":"paragraph","content":[
              {"type":"text","text":"<script>alert(1)</script>"}]}]}
            """);
        var clean = sanitizer.sanitize(3L, content);
        assertEquals("<script>alert(1)</script>", clean.at("/content/0/content/0/text").asText());
        assertTrue(clean.toString().contains("<script>")); // JSON text, never executable HTML
    }

    @Test void rejectsHtmlNodesAndJavascriptLinks() throws Exception {
        assertThrows(BadRequestException.class, () -> sanitizer.sanitize(3L, mapper.readTree("""
            {"type":"doc","content":[{"type":"html","text":"<img src=x onerror=alert(1)>"}]}
            """)));
        assertThrows(BadRequestException.class, () -> sanitizer.sanitize(3L, mapper.readTree("""
            {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"x",
              "marks":[{"type":"link","attrs":{"href":"javascript:alert(1)"}}]}]}]}
            """)));
    }

    @Test void stripsUntrustedMentionLabelAndRequiresProjectMembership() throws Exception {
        var payload = mapper.readTree("""
            {"type":"doc","content":[{"type":"paragraph","content":[
              {"type":"mention","attrs":{"id":"7","label":"<h1>Fake</h1>"}}]}]}
            """);
        assertThrows(BadRequestException.class, () -> sanitizer.sanitize(3L, payload));
        when(members.existsByProjectIdAndUserId(3L, 7L)).thenReturn(true);
        when(users.findById(7L)).thenReturn(Optional.of(User.builder().id(7L).firstName("Jane").lastName("Doe").build()));
        var safe = sanitizer.sanitize(3L, payload);
        assertEquals("Jane Doe", safe.at("/content/0/content/0/attrs/label").asText());
    }

    @Test void rejectsEmptyOversizedAndUnknownFields() throws Exception {
        assertThrows(BadRequestException.class, () -> sanitizer.sanitize(3L, mapper.readTree("""
            {"type":"doc","content":[]}
            """)));
        assertThrows(BadRequestException.class, () -> sanitizer.sanitize(3L,
                mapper.readTree("{\"type\":\"doc\",\"innerHTML\":\"x\"}")));
        String huge = "x".repeat(10001);
        var hugeDoc = mapper.createObjectNode().put("type", "doc");
        hugeDoc.putArray("content").add(mapper.createObjectNode().put("type", "text").put("text", huge));
        assertThrows(BadRequestException.class, () -> sanitizer.sanitize(3L, hugeDoc));
    }
}
