package team.sprintory_backend.mention.dto;

import java.util.List;

/**
 * The @ suggestion list of {@code GET /projects/{projectId}/mentionables}
 * (docs/mentions-docs.md §3): matching people first, then matching teams.
 */
public record MentionablesResponse(List<UserItem> users, List<TeamItem> teams) {

    /** A project member who can be mentioned. */
    public record UserItem(Long id, String name, String email) {
    }

    /** A team with {@code membersInProject} of its members in the project: the people it reaches. */
    public record TeamItem(Long id, String name, long membersInProject) {
    }
}