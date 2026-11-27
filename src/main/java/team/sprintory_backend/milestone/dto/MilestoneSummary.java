package team.sprintory_backend.milestone.dto;

import team.sprintory_backend.milestone.models.Milestone;

import java.time.LocalDate;

/** The {@code milestone} field of every task response (docs/milestones-docs.md §5). */
public record MilestoneSummary(Long id, String name, LocalDate targetDate) {

    /** {@code null} for no milestone. */
    public static MilestoneSummary of(Milestone milestone) {
        return milestone == null ? null
                : new MilestoneSummary(milestone.getId(), milestone.getName(), milestone.getTargetDate());
    }
}
