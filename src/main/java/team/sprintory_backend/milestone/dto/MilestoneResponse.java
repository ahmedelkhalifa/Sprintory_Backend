package team.sprintory_backend.milestone.dto;

import team.sprintory_backend.common.dto.UserRef;
import team.sprintory_backend.milestone.models.MilestoneStatus;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A milestone with its calculated status and progress (docs/milestones-docs.md §2).
 * {@code canManage} / {@code canLink}: the caller holds the permission and the workspace and
 * project are writable.
 */
public record MilestoneResponse(
        Long id,
        Long projectId,
        String name,
        String description,
        LocalDate targetDate,
        MilestoneStatus status,
        MilestoneProgress progress,
        Instant createdAt,
        UserRef createdBy,
        boolean canManage,
        boolean canLink
) {
}
