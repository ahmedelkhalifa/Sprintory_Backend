package team.sprintory_backend.milestone.dto;

/** docs/milestones-docs.md §3.8: the task's new milestone; {@code null} or left out unlinks it. */
public record TaskMilestoneRequest(Long milestoneId) {
}
