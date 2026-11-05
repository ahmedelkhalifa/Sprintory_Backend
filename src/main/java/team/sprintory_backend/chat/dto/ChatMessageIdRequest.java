package team.sprintory_backend.chat.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ChatMessageIdRequest(@NotNull @Positive Long messageId) {}
