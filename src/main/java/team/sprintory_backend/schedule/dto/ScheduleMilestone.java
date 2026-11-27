package team.sprintory_backend.schedule.dto;

import java.time.LocalDate;

/** Contract for the PMP-46 integration; no milestone table is assumed here. */
public record ScheduleMilestone(Long id, String title, LocalDate targetDate) {}
