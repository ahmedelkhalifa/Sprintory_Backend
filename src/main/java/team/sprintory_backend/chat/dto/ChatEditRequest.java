package team.sprintory_backend.chat.dto;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ChatEditRequest(@NotNull @Positive Long messageId, @NotNull JsonNode content) {}
