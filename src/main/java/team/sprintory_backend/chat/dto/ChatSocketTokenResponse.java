package team.sprintory_backend.chat.dto;

public record ChatSocketTokenResponse(String token, int expiresInSeconds) {}
