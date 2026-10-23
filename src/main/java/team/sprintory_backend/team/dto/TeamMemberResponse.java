package team.sprintory_backend.team.dto;

import team.sprintory_backend.team.models.TeamMember;
import team.sprintory_backend.user.models.User;

/** Public profile data only: never include password hashes or tokens here. */
public record TeamMemberResponse(Long userId, String firstName, String lastName, String email) {
    public static TeamMemberResponse from(TeamMember membership) {
        User user = membership.getUser();
        return new TeamMemberResponse(user.getId(), user.getFirstName(), user.getLastName(), user.getEmail());
    }
}