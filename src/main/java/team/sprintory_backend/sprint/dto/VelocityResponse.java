package team.sprintory_backend.sprint.dto;

import team.sprintory_backend.sprint.models.Sprint;

import java.time.Instant;
import java.util.List;

/** docs/burndown-docs.md §4. {@code sprints} is oldest first. */
public record VelocityResponse(List<SprintPoints> sprints, Double averageVelocity, int averageOf) {

    public record SprintPoints(Long id, String name, Integer committedPoints, Integer completedPoints,
                               Instant completedAt) {
        public static SprintPoints of(Sprint sprint) {
            return new SprintPoints(sprint.getId(), sprint.getName(), sprint.getCommittedPoints(),
                    sprint.getCompletedPoints(), sprint.getCompletedAt());
        }
    }
}
