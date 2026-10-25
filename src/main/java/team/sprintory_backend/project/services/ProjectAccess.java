package team.sprintory_backend.project.services;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectMember;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.services.WorkspaceAccess;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Project-level access checks (R03, R04), the project counterpart of {@link WorkspaceAccess}.
 * Every check on something inside a project goes through here and asks for a
 * {@link ProjectPermission}, never for a role.
 *
 * <p>A project is visible to its members and to the admins of its workspace; anyone else gets
 * the same 404 as a missing project (docs/api-conventions.md §5). Someone who can see the
 * project but lacks the permission gets 403. Use {@link #requireActive} for anything that
 * changes data: it also refuses an archived workspace with 409.
 */
@Component
@RequiredArgsConstructor
public class ProjectAccess {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final WorkspaceAccess workspaceAccess;

    /** 404 unless the caller is a member of the project or an admin of its workspace. */
    public ProjectAccessResult requireVisible(Long projectId, User user) {
        Project project = projectRepository.findWithWorkspaceById(projectId)
                .orElseThrow(ProjectAccess::projectNotFound);
        WorkspaceMember workspaceMember = workspaceAccess.findMember(project.getWorkspace().getId(), user)
                .orElseThrow(ProjectAccess::projectNotFound);
        ProjectMember projectMember = projectMemberRepository
                .findWithRolesByProjectIdAndUserId(projectId, user.getId())
                .orElse(null);
        if (projectMember == null && !workspaceMember.isAdmin()) {
            throw projectNotFound();
        }
        return new ProjectAccessResult(project, workspaceMember, projectMember,
                permissionsOf(workspaceMember, projectMember));
    }

    /** As {@link #requireVisible}, and 403 unless the caller has the permission. */
    public ProjectAccessResult require(Long projectId, User user, ProjectPermission permission) {
        ProjectAccessResult access = requireVisible(projectId, user);
        if (!access.can(permission)) {
            throw new AccessDeniedException("Project permission required: " + permission);
        }
        return access;
    }

    /** As {@link #require}, and 409 if the workspace is archived. For anything that changes data. */
    public ProjectAccessResult requireActive(Long projectId, User user, ProjectPermission permission) {
        ProjectAccessResult access = require(projectId, user, permission);
        WorkspaceAccess.requireNotArchived(access.workspaceMember());
        return access;
    }

    private static Set<ProjectPermission> permissionsOf(WorkspaceMember workspaceMember,
                                                        ProjectMember projectMember) {
        Set<ProjectPermission> permissions = (projectMember != null)
                ? ProjectRole.permissionsOf(projectMember.getRoles())
                : EnumSet.noneOf(ProjectPermission.class);
        if (workspaceMember.isAdmin()) {
            permissions.addAll(ProjectPermission.WORKSPACE_ADMIN);
        }
        return Collections.unmodifiableSet(permissions);
    }

    private static ResourceNotFoundException projectNotFound() {
        return new ResourceNotFoundException("Project not found.");
    }
}