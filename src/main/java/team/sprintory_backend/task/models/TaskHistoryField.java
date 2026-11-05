package team.sprintory_backend.task.models;

/**
 * The fields a {@link TaskHistoryType#FIELD_CHANGED} entry can be about
 * (docs/task-history-docs.md §3.2). A new value needs a migration changing
 * {@code ck_task_history_field}.
 */
public enum TaskHistoryField {
    TITLE,
    TYPE,
    PRIORITY,
    STATUS,
    ASSIGNEE,
    /** Story points, top-level tasks only. */
    ESTIMATE,
    /** Time estimate, subtasks only. */
    ESTIMATE_MINUTES,
    LABELS,
    /** Recorded without text: old and new values are always null. */
    DESCRIPTION
}