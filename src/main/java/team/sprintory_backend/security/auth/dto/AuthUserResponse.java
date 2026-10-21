package team.sprintory_backend.security.auth.dto;

import team.sprintory_backend.user.models.User;

/** Public profile data only: never include password hashes or tokens here. */
public record AuthUserResponse(Long id, String firstName, String lastName, String email) {
    public static AuthUserResponse from(User user) {
        return new AuthUserResponse(user.getId(), user.getFirstName(), user.getLastName(), user.getEmail());
    }
}
