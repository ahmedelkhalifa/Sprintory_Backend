package team.sprintory_backend.milestone.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import team.sprintory_backend.milestone.models.Milestone;

import java.time.LocalDate;

/**
 * Create or edit a milestone (docs/milestones-docs.md §3.2, §3.4); an edit replaces all three
 * fields. Name and description are trimmed before validation, and a blank description is none.
 */
public record MilestoneRequest(
        @NotBlank @Size(max = Milestone.NAME_MAX) String name,
        @NotNull LocalDate targetDate,
        @Size(max = Milestone.DESCRIPTION_MAX) String description
) {
    public MilestoneRequest {
        name = name == null ? null : name.trim();
        description = description == null || description.isBlank() ? null : description.trim();
    }
}
