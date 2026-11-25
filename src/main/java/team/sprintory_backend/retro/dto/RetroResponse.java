package team.sprintory_backend.retro.dto;

import team.sprintory_backend.retro.models.RetroStatus;
import team.sprintory_backend.sprint.models.Sprint;
import team.sprintory_backend.sprint.models.SprintGoalAchieved;
import team.sprintory_backend.sprint.models.SprintStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A whole retro board in one response (docs/retrospectives-docs.md §2). {@code votesLeft},
 * {@code canContribute} and {@code canManage} are for the caller.
 */
public record RetroResponse(Long id,
                            Long projectId,
                            String title,
                            RetroStatus status,
                            Instant createdAt,
                            RetroUser createdBy,
                            Instant closedAt,
                            RetroUser closedBy,
                            SprintSummary sprint,
                            List<RetroItemResponse> items,
                            int votesPerMember,
                            int votesLeft,
                            List<RetroActionResponse> actions,
                            PreviousActions previousActions,
                            boolean canContribute,
                            boolean canManage) {

    /** The ended sprint the retro is about; the end fields are frozen on the sprint. */
    public record SprintSummary(Long id,
                                String name,
                                SprintStatus status,
                                String goal,
                                LocalDate startDate,
                                LocalDate endDate,
                                Instant completedAt,
                                SprintGoalAchieved goalAchieved,
                                String goalNote,
                                String cancelReason) {

        public static SprintSummary of(Sprint sprint) {
            return new SprintSummary(sprint.getId(), sprint.getName(), sprint.getStatus(), sprint.getGoal(),
                    sprint.getStartDate(), sprint.getEndDate(), sprint.getCompletedAt(),
                    sprint.getGoalAchieved(), sprint.getOutcomeNote(), sprint.getCancelReason());
        }
    }

    /** The unfinished actions of the most recent earlier retro of the project. */
    public record PreviousActions(Long retroId, String sprintName, List<RetroActionResponse> actions) {
    }
}
