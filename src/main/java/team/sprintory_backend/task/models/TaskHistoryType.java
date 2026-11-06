package team.sprintory_backend.task.models;

/**
 * The kinds of task history entries (docs/task-history-docs.md §3.1). A new value needs a
 * migration changing {@code ck_task_history_type}.
 */
public enum TaskHistoryType {
    CREATED,
    /** One field changed; {@link TaskHistoryField} says which. */
    FIELD_CHANGED,
    COMMENT_DELETED,
    /** On the parent: a subtask was created, or a subtask deleted on its own was restored. */
    SUBTASK_ADDED,
    /** On the parent: one of its subtasks was deleted on its own. */
    SUBTASK_REMOVED,
    RESTORED
}