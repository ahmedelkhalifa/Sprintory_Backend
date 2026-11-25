package team.sprintory_backend.retro.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import team.sprintory_backend.retro.models.RetroColumn;

/**
 * Adding a card (docs/retrospectives-docs.md §3.3): {@code body} is a string holding a TipTap
 * document. An edit sends only the body and uses {@code CommentRequest}, which has that shape.
 */
public record RetroItemRequest(@NotNull RetroColumn column, @NotBlank String body) {
}
