package team.sprintory_backend.chat.dto;

import java.util.List;

/** Messages oldest-to-newest. nextBefore is exclusive; null means no older messages. */
public record ChatHistoryResponse(List<ChatMessageResponse> messages, Long nextBefore, boolean hasMore) {}
