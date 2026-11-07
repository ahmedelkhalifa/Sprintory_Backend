package team.sprintory_backend.search.dto;

/**
 * Stable, lightweight search hit. Task key is set only for TASK hits; parent key only for a
 * subtask (PMP-132), so a null {@code parentKey} on a task means a top-level task.
 */
public record UniversalSearchResult(
        SearchResultType type,
        Long id,
        String title,
        String subtitle,
        String path,
        Long workspaceId,
        Long projectId,
        String taskKey,
        String parentKey
) {
    /** Hits that are not subtasks. */
    public UniversalSearchResult(SearchResultType type, Long id, String title, String subtitle, String path,
                                 Long workspaceId, Long projectId, String taskKey) {
        this(type, id, title, subtitle, path, workspaceId, projectId, taskKey, null);
    }
}