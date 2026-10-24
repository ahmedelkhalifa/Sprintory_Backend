package team.sprintory_backend.project.services;

import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectMember;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.workspace.models.WorkspaceMember;

import java.util.Set;

/**
 * What {@link ProjectAccess} found out about the caller and one project.
 *
 * @param project          the project, with its workspace loaded
 * @param workspaceMember  the caller's membership in the project's workspace
 * @param projectMember    the caller's membership in the project, with roles; {@code null} for a
 *                         workspace admin who is not in the project
 * @param permissions      everything the caller may do here: their project roles combined, plus
 *                         {@link ProjectPermission#WORKSPACE_ADMIN} for an admin. Unmodifiable.
 */
public record ProjectAccessResult(Project project,
                                  WorkspaceMember workspaceMember,
                                  ProjectMember projectMember,
                                  Set<ProjectPermission> permissions) {

    public boolean can(ProjectPermission permission) {
        return permissions.contains(permission);
    }
}