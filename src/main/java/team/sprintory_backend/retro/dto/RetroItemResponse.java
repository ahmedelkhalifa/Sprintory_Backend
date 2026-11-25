package team.sprintory_backend.retro.dto;

import team.sprintory_backend.comment.dto.CommentResponse;
import team.sprintory_backend.retro.models.RetroColumn;

import java.time.Instant;
import java.util.List;

/**
 * One live card (docs/retrospectives-docs.md §2). {@code body} is the stored, normalized TipTap
 * JSON; {@code mentions} carries the current names, as for comments. {@code votedByMe},
 * {@code canEdit} and {@code canDelete} are for the caller.
 */
public record RetroItemResponse(Long id,
                                RetroColumn column,
                                RetroUser author,
                                String body,
                                List<CommentResponse.MentionName> mentions,
                                Instant createdAt,
                                Instant editedAt,
                                int voteCount,
                                boolean votedByMe,
                                boolean canEdit,
                                boolean canDelete) {
}
