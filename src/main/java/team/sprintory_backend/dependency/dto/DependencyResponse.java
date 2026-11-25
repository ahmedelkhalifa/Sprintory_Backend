package team.sprintory_backend.dependency.dto;

public record DependencyResponse(Long id, DependencyTaskSummary blocker, DependencyTaskSummary blocked) {
}
