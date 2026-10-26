package team.sprintory_backend.project.dto;

import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.models.ProjectTemplate;

import java.util.Set;

/**
 * Project as seen by the caller. Roles are the caller's own membership roles (empty if a
 * workspace admin is not in the project). Permissions also include their workspace-admin grants.
 * The template is immutable after project creation.
 */
public record ProjectResponse(Long id, Long workspaceId, String name, String key, String description,
                              ProjectTemplate template, Set<ProjectRole> roles,
                              Set<ProjectPermission> permissions) {
    public static ProjectResponse from(Project project, Set<ProjectRole> roles,
                                       Set<ProjectPermission> permissions) {
        return new ProjectResponse(project.getId(), project.getWorkspace().getId(), project.getName(),
                project.getKey(), project.getDescription(), project.getTemplate(),
                Set.copyOf(roles), Set.copyOf(permissions));
    }
}
