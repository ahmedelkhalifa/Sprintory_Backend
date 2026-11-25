package team.sprintory_backend.retro.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Creating an action item (docs/retrospectives-docs.md §3.8). The title becomes the task's title,
 * the owner its assignee. {@code itemId} links the card it came from; {@code sprintId} is a
 * PLANNED sprint, omitted for the backlog.
 */
public record RetroActionRequest(@NotBlank @Size(max = 255) String title,
                                 @NotNull Long ownerId,
                                 Long itemId,
                                 Long sprintId) {
}
