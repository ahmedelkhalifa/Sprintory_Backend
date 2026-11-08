package team.sprintory_backend.sprint.dto;

import team.sprintory_backend.sprint.models.Sprint;
import team.sprintory_backend.sprint.models.SprintStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record SprintResponse(Long id, Long projectId, String name, String goal, Integer capacity,
                             LocalDate startDate, LocalDate endDate, SprintStatus status,
                             List<Long> taskIds, Instant completedAt,
                             Integer committedPoints, Integer completedPoints,
                             int selectedPoints, List<Long> unestimatedTaskIds, boolean overCapacity) {
    public static SprintResponse of(Sprint sprint, List<Long> taskIds, int selectedPoints, List<Long> unestimatedTaskIds) {
        return new SprintResponse(sprint.getId(), sprint.getProject().getId(), sprint.getName(),
                sprint.getGoal(), sprint.getCapacity(), sprint.getStartDate(), sprint.getEndDate(),
                sprint.getStatus(), List.copyOf(taskIds), sprint.getCompletedAt(),
                sprint.getCommittedPoints(), sprint.getCompletedPoints(), selectedPoints,
                List.copyOf(unestimatedTaskIds), sprint.getCapacity() != null && selectedPoints > sprint.getCapacity());
    }
}
