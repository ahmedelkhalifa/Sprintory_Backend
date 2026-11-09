package team.sprintory_backend.task.dto;

import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.models.TaskPriority;
import team.sprintory_backend.task.models.TaskType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** Public task representation, never exposing the soft-delete marker or database entities. */
public record TaskResponse(
        Long id,
        Long projectId,
        String key,
        String title,
        TaskType type,
        TaskPriority priority,
        Long statusId,
        String statusName,
        Long assigneeId,
        Integer estimate,
        List<String> labels,
        String description,
        Long sprintId,
        Instant createdAt,
        Instant updatedAt,
        WipWarning wipWarning,
        Long parentTaskId,
        Integer estimateMinutes,
        int subtaskTotal,
        int subtaskDone,
        /** "Carried over ×N" (PMP-41): ended sprints this task left unfinished; 0 for subtasks. */
        int carriedOverCount
) {
    public TaskResponse(Long id, Long projectId, String key, String title, TaskType type,
                        TaskPriority priority, Long statusId, String statusName, Long assigneeId,
                        Integer estimate, List<String> labels, String description, Long sprintId,
                        Instant createdAt, Instant updatedAt) {
        this(id, projectId, key, title, type, priority, statusId, statusName, assigneeId,
                estimate, labels, description, sprintId, createdAt, updatedAt, null, null, null, 0, 0, 0);
    }

    /** Preserve the PMP-125 16-argument constructor for existing callers. */
    public TaskResponse(Long id, Long projectId, String key, String title, TaskType type,
                        TaskPriority priority, Long statusId, String statusName, Long assigneeId,
                        Integer estimate, List<String> labels, String description, Long sprintId,
                        Instant createdAt, Instant updatedAt, WipWarning wipWarning) {
        this(id, projectId, key, title, type, priority, statusId, statusName, assigneeId,
                estimate, labels, description, sprintId, createdAt, updatedAt, wipWarning,
                null, null, 0, 0, 0);
    }

    public TaskResponse withProgress(int total, int done) {
        return new TaskResponse(id, projectId, key, title, type, priority, statusId, statusName,
                assigneeId, estimate, labels, description, sprintId, createdAt, updatedAt,
                wipWarning, parentTaskId, estimateMinutes, total, done, carriedOverCount);
    }

    public TaskResponse withWipWarning(WipWarning warning) {
        return new TaskResponse(id, projectId, key, title, type, priority, statusId, statusName,
                assigneeId, estimate, labels, description, sprintId, createdAt, updatedAt, warning,
                parentTaskId, estimateMinutes, subtaskTotal, subtaskDone, carriedOverCount);
    }

    /** Labels sorted for stable order; sprintId reflects the parent sprint for subtasks. */
    public static TaskResponse from(Task task) {
        return new TaskResponse(task.getId(), task.getProject().getId(), task.getKey(), task.getTitle(),
                task.getType(), task.getPriority(), task.getStatus().getId(), task.getStatus().getName(),
                task.getAssignee() == null ? null : task.getAssignee().getId(),
                task.getParent() == null ? task.getEstimate() : null,
                task.getLabels().stream().sorted().toList(), task.getDescription(),
                (task.getParent() == null
                        ? (task.getSprint() == null ? null : task.getSprint().getId())
                        : (task.getParent().getSprint() == null ? null : task.getParent().getSprint().getId())),
                toInstant(task.getCreatedAt()), toInstant(task.getUpdatedAt()), null,
                task.getParent() == null ? null : task.getParent().getId(), task.getEstimateMinutes(), 0, 0,
                task.getParent() == null ? task.getCarryOverCount() : 0);
    }

    /** The audit columns are local date-times written in the server's zone. */
    private static Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }
}