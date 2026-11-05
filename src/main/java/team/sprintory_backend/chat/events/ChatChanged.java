package team.sprintory_backend.chat.events;

import team.sprintory_backend.chat.dto.ChatMessageResponse;

/** Immutable snapshot; emitted by service in its transaction and broadcast AFTER_COMMIT. */
public record ChatChanged(String type, Long projectId, ChatMessageResponse message) {}
