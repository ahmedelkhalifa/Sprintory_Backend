package team.sprintory_backend.schedule.dto;

import java.util.List;

public record ScheduleConflict(ScheduleConflictType type, List<Long> taskIds, Long dependencyId,
                               Long milestoneId, String message) {}
