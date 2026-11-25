package team.sprintory_backend.retro.dto;

import team.sprintory_backend.project.models.BoardColumnCategory;

import java.time.Instant;

/**
 * An action item with its task's live state (docs/retrospectives-docs.md §2). {@code itemId} is
 * null when the action was created without a card.
 */
public record RetroActionResponse(Long id, Long itemId, Instant createdAt, TaskRef task) {

    /**
     * The task as it is now. A deleted task has only {@code id}, {@code key} and
     * {@code deleted = true}; every other field is null.
     */
    public record TaskRef(Long id,
                          String key,
                          String title,
                          String statusName,
                          BoardColumnCategory statusCategory,
                          RetroUser assignee,
                          Long sprintId,
                          String sprintName,
                          boolean deleted) {
    }
}
