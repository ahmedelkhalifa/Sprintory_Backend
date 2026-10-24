package team.sprintory_backend.project.dto;

import team.sprintory_backend.project.models.ProjectMember;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.user.models.User;

import java.util.Set;

/** Public profile and project roles only; never include credentials or tokens. */
public record ProjectMemberResponse(Long userId, String firstName, String lastName, String email,
                                    Set<ProjectRole> roles) {
    public static ProjectMemberResponse from(ProjectMember membership) {
        User user = membership.getUser();
        return new ProjectMemberResponse(user.getId(), user.getFirstName(), user.getLastName(),
                user.getEmail(), Set.copyOf(membership.getRoles()));
    }
}
