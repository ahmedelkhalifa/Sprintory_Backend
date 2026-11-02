package team.sprintory_backend.task.dto;

/** Soft Kanban limit: the move succeeds even when this warning is present. */
public record WipWarning(Long columnId, long count, int limit, String message) { }
