package team.sprintory_backend.search.dto;

/** Stable, lightweight search hit. Task key is set only for TASK hits. */
public record UniversalSearchResult(
        SearchResultType type,
        Long id,
        String title,
        String subtitle,
        String path,
        Long workspaceId,
        Long projectId,
        String taskKey
) {
}
