package team.sprintory_backend.chat.dto;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

/** TipTap document JSON; never rendered or accepted as raw HTML. */
public record ChatMessageRequest(@NotNull JsonNode content) {}
