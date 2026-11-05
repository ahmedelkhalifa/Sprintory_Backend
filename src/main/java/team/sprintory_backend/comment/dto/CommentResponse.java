package team.sprintory_backend.comment.dto;

import java.time.Instant;
import java.util.List;

/**
 * One comment of a thread (docs/comments-docs.md §2). A deleted comment keeps its author and
 * times but has no body and no mentions. {@code canEdit} and {@code canDelete} are for the caller.
 */
public record CommentResponse(Long id,
                              Long taskId,
                              Author author,
                              String body,
                              List<MentionName> mentions,
                              Instant createdAt,
                              Instant editedAt,
                              boolean deleted,
                              Instant deletedAt,
                              boolean canEdit,
                              boolean canDelete) {

    /** Null in the response when the author's account no longer exists. */
    public record Author(Long id, String name) {
    }

    /**
     * The current name of a person or team mentioned in the body; {@code kind} is "user" or
     * "team", as in the mention node. {@code name} is null when the person or team no longer exists.
     */
    public record MentionName(String kind, Long id, String name) {
    }
}