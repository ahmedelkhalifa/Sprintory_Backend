package team.sprintory_backend.schedule.api;

import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.schedule.dto.ScheduleTask;
import team.sprintory_backend.task.models.TaskType;

import java.time.LocalDate;
import java.util.Objects;

public record ScheduleFilters(Long assigneeId, Long statusId, BoardColumnCategory statusCategory,
                              Long milestoneId, Long sprintId, TaskType type, Boolean hideDone,
                              LocalDate from, LocalDate to) {
    public void validate() {
        if (from != null && to != null && from.isAfter(to))
            throw new BadRequestException("Schedule from must be on or before to.");
        for (Long id : new Long[]{assigneeId, statusId, milestoneId, sprintId}) {
            if (id != null && id <= 0) throw new BadRequestException("Schedule filter IDs must be positive.");
        }
    }

    public boolean matches(ScheduleTask task) {
        return task.scheduled()
                && (assigneeId == null || Objects.equals(assigneeId, task.assigneeId()))
                && (statusId == null || Objects.equals(statusId, task.statusId()))
                && (statusCategory == null || statusCategory == task.statusCategory())
                && (milestoneId == null || Objects.equals(milestoneId, task.milestoneId()))
                && (sprintId == null || Objects.equals(sprintId, task.sprintId()))
                && (type == null || type == task.type())
                && (!Boolean.TRUE.equals(hideDone) || task.statusCategory() != BoardColumnCategory.DONE)
                && (from == null || !task.dueDate().isBefore(from))
                && (to == null || !task.startDate().isAfter(to));
    }
}
