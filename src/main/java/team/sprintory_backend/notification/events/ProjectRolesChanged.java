package team.sprintory_backend.notification.events;

import java.util.Set;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.user.models.User;

/** A member's project roles changed; {@code roles} is the new set. Publish only on a real change. */
public record ProjectRolesChanged(Project project, User actor, User member, Set<ProjectRole> roles) {

    public ProjectRolesChanged {
        roles = Set.copyOf(roles);
    }
}
