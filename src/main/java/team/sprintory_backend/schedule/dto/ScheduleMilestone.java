package team.sprintory_backend.schedule.dto;

import java.time.LocalDate;

/** A live milestone of the project (PMP-46, docs/milestones-docs.md): a diamond on its target date. */
public record ScheduleMilestone(Long id, String name, LocalDate targetDate) {}
