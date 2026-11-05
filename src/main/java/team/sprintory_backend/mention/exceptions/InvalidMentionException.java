package team.sprintory_backend.mention.exceptions;

/**
 * A newly added mention is outside the project: a person who is not a member, or a team with no
 * member in it. Answered with {@code 400 INVALID_MENTION} (docs/mentions-docs.md §4).
 */
public class InvalidMentionException extends RuntimeException {

    public InvalidMentionException(String message) {
        super(message);
    }
}