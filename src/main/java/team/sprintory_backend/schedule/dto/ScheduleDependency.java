package team.sprintory_backend.schedule.dto;

public record ScheduleDependency(Long id, Long blockerId, Long blockedId) {}
