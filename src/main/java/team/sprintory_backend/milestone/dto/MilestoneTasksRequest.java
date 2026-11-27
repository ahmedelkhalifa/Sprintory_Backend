package team.sprintory_backend.milestone.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** docs/milestones-docs.md §3.7: the tasks to link, all or nothing. Duplicates are ignored. */
public record MilestoneTasksRequest(@NotNull @Size(min = 1, max = 100) List<@NotNull Long> taskIds) {
}
