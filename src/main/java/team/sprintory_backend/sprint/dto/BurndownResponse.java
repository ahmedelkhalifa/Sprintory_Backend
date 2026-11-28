package team.sprintory_backend.sprint.dto;

import team.sprintory_backend.sprint.models.BurndownDays;
import team.sprintory_backend.sprint.models.BurndownStatus;
import team.sprintory_backend.sprint.models.BurndownUnit;
import team.sprintory_backend.sprint.models.SprintStatus;

import java.time.LocalDate;
import java.util.List;

/** docs/burndown-docs.md §3.2. Index 0 of every array is the Start point. */
public record BurndownResponse(Long sprintId, String sprintName, SprintStatus sprintStatus,
        LocalDate startDate, LocalDate endDate, LocalDate endedOn, LocalDate today,
        BurndownUnit unit, BurndownDays days, boolean hasDailyData,
        List<LocalDate> dates, List<Double> ideal, List<Long> actual, List<Long> scopeChanges,
        Summary summary, BurndownStatus status) {

    public record Summary(Long committed, Long currentScope, Long scopeChange, Long done, Long remaining,
                          int daysLeft, Long unestimatedTasks) {}
}
