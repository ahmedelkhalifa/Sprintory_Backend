package team.sprintory_backend.chat.dto;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/** An additive mentions field supplies current names; content stays a TipTap JSON object. */
public record ChatMessageResponse(Long id, Long projectId, Long authorId, String authorName,
                                  JsonNode content, Instant createdAt, Instant editedAt, boolean deleted,
                                  List<MentionName> mentions) {
    public record MentionName(String kind, Long id, String name) {}
}
