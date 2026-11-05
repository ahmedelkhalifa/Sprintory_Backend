package team.sprintory_backend.comment.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Posting or editing a comment: {@code body} is a string holding a TipTap document
 * (docs/mentions-docs.md §2). An edit sends the full new body.
 */
public record CommentRequest(@NotBlank String body) {
}