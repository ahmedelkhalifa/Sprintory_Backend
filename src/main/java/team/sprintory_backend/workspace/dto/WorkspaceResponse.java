package team.sprintory_backend.workspace.dto;

import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;

import java.time.Instant;

/**
 * A workspace as seen by one member, so it carries that member's role.
 * {@code archivedAt} is null while the workspace is active.
 */
public record WorkspaceResponse(Long id, String name, String slug, Role role, Instant archivedAt) {
    public static WorkspaceResponse from(WorkspaceMember membership) {
        Workspace workspace = membership.getWorkspace();
        return new WorkspaceResponse(workspace.getId(), workspace.getName(), workspace.getSlug(),
                membership.getRole(), workspace.getArchivedAt());
    }
}