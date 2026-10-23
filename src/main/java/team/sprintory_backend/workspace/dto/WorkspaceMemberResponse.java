package team.sprintory_backend.workspace.dto;

import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.models.WorkspaceMember;

/** Public profile data and workspace role only: never include password hashes or tokens here. */
public record WorkspaceMemberResponse(Long userId, String firstName, String lastName, String email,
                                      Role role) {
    public static WorkspaceMemberResponse from(WorkspaceMember membership) {
        User user = membership.getUser();
        return new WorkspaceMemberResponse(user.getId(), user.getFirstName(), user.getLastName(),
                user.getEmail(), membership.getRole());
    }
}