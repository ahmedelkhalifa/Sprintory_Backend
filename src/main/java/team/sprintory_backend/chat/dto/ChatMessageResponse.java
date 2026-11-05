package team.sprintory_backend.chat.dto;

import tools.jackson.databind.JsonNode;
import java.time.Instant;

public record ChatMessageResponse(Long id, Long projectId, Long authorId, String authorName,
                                  JsonNode content, Instant createdAt, Instant editedAt, boolean deleted) {}
