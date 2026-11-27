package team.sprintory_backend.schedule.dto;

import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.task.models.TaskType;

import java.time.LocalDate;

/** Lightweight row: deliberately excludes rich text and other task detail fields. */
public record ScheduleTask(Long id, String key, String title, LocalDate startDate, LocalDate dueDate,
                           BoardColumnCategory statusCategory, Long statusId, String statusName,
                           TaskType type, Long assigneeId, String assignee, Long sprintId, Long milestoneId) {
    public boolean scheduled() { return startDate != null && dueDate != null; }

    /** Constructor used by the scalar projection until PMP-46 supplies milestone associations. */
    public ScheduleTask(Long id, String key, String title, LocalDate startDate, LocalDate dueDate,
                        BoardColumnCategory statusCategory, Long statusId, String statusName,
                        TaskType type, Long assigneeId, String assignee, Long sprintId) {
        this(id, key, title, startDate, dueDate, statusCategory, statusId, statusName,
                type, assigneeId, assignee, sprintId, null);
    }
}
