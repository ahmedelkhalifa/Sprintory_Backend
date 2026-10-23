package team.sprintory_backend.project.dto;

import team.sprintory_backend.project.models.ProjectMember;
import team.sprintory_backend.user.models.User;

/** Public profile data only: never include password hashes or tokens here. Story 6 adds roles. */
public record ProjectMemberResponse(Long userId, String firstName, String lastName, String email) {
    public static ProjectMemberResponse from(ProjectMember membership) {
        User user = membership.getUser();
        return new ProjectMemberResponse(user.getId(), user.getFirstName(), user.getLastName(), user.getEmail());
    }
}