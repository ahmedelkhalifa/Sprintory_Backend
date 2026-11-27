package team.sprintory_backend.schedule.dto;

import java.time.LocalDate;
import java.util.List;

public record ScheduleResponse(LocalDate today, LocalDate earliestStartDate, LocalDate latestDueDate,
                               boolean canEditDates, boolean milestonesAvailable,
                               List<ScheduleTask> tasks, List<ScheduleDependency> dependencies,
                               List<ScheduleMilestone> milestones, List<ScheduleConflict> conflicts,
                               List<UnscheduledTask> unscheduled) {}
