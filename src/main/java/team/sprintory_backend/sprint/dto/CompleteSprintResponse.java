package team.sprintory_backend.sprint.dto;

public record CompleteSprintResponse(SprintResponse sprint, int carriedOver, SprintResponse toSprint) {}
