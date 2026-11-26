package team.sprintory_backend.schedule.dto;

import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.task.models.TaskType;

import java.util.List;

public record UnscheduledTask(Long id, String key, String title, TaskType type,
                              BoardColumnCategory statusCategory, Long statusId, String statusName,
                              Long assigneeId, String assignee, Long sprintId, Long milestoneId,
                              List<String> blockerKeys, List<String> blockedTaskKeys, String hint) {}
