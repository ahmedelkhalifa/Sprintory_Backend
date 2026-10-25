package team.sprintory_backend.workspace.services;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.common.Pageables;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.exceptions.LastProjectManagerException;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.team.repositories.TeamMemberRepository;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.dto.CreateWorkspaceRequest;
import team.sprintory_backend.workspace.dto.UpdateWorkspaceRequest;
import team.sprintory_backend.workspace.dto.WorkspaceMemberResponse;
import team.sprintory_backend.workspace.dto.WorkspaceResponse;
import team.sprintory_backend.workspace.exceptions.LastWorkspaceAdminException;
import team.sprintory_backend.workspace.exceptions.WorkspaceNotArchivedException;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;
import team.sprintory_backend.workspace.repositories.WorkspaceRepository;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * R02 workspace logic: create, read, rename, archive, restore and permanently delete; R03 role
 * changes and removing or leaving members. Access rules are in {@link WorkspaceAccess}.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceAccess workspaceAccess;
    private final SlugGenerator slugGenerator;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final TeamMemberRepository teamMemberRepository;

    /** Creates an empty workspace and makes the caller its admin. */
    @Transactional
    public WorkspaceResponse create(User user, CreateWorkspaceRequest request) {
        String name = request.name().trim();
        // Flush now: a concurrent workspace that took the same slug fails here with 409.
        Workspace workspace = workspaceRepository.saveAndFlush(Workspace.builder()
                .name(name)
                .slug(slugGenerator.generate(name))
                .build());
        WorkspaceMember admin = workspaceMemberRepository.save(WorkspaceMember.builder()
                .workspace(workspace)
                .user(user)
                .role(Role.WORKSPACE_ADMIN)
                .build());
        return WorkspaceResponse.from(admin);
    }

    /** Every workspace the caller belongs to, archived ones included. */
    public List<WorkspaceResponse> listMine(User user) {
        return workspaceMemberRepository.findAllByUserId(user.getId()).stream()
                .map(WorkspaceResponse::from)
                .toList();
    }

    public WorkspaceResponse get(User user, Long workspaceId) {
        return WorkspaceResponse.from(workspaceAccess.requireMember(workspaceId, user));
    }

    /** One page of members, optionally filtered by name or email ({@code q}). */
    public PageResponse<WorkspaceMemberResponse> listMembers(User user, Long workspaceId, String q,
                                                             Pageable pageable) {
        workspaceAccess.requireMember(workspaceId, user);
        return PageResponse.from(workspaceMemberRepository
                .searchByWorkspaceId(workspaceId, Pageables.containsPattern(q), Pageables.withoutSort(pageable))
                .map(WorkspaceMemberResponse::from));
    }

    /** Renames the workspace. The slug is kept, so existing links keep working. */
    @Transactional
    public WorkspaceResponse rename(User user, Long workspaceId, UpdateWorkspaceRequest request) {
        WorkspaceMember admin = workspaceAccess.requireActiveAdmin(workspaceId, user);
        Workspace workspace = admin.getWorkspace();
        workspace.setName(request.name().trim());
        workspaceRepository.save(workspace);
        return WorkspaceResponse.from(admin);
    }

    /** Makes the workspace read-only. Archiving an archived workspace changes nothing. */
    @Transactional
    public WorkspaceResponse archive(User user, Long workspaceId) {
        WorkspaceMember admin = workspaceAccess.requireAdmin(workspaceId, user);
        Workspace workspace = admin.getWorkspace();
        if (!workspace.isArchived()) {
            workspace.setArchivedAt(Instant.now());
            workspaceRepository.save(workspace);
        }
        return WorkspaceResponse.from(admin);
    }

    /** Makes an archived workspace editable again. Restoring an active one changes nothing. */
    @Transactional
    public WorkspaceResponse restore(User user, Long workspaceId) {
        WorkspaceMember admin = workspaceAccess.requireAdmin(workspaceId, user);
        Workspace workspace = admin.getWorkspace();
        if (workspace.isArchived()) {
            workspace.setArchivedAt(null);
            workspaceRepository.save(workspace);
        }
        return WorkspaceResponse.from(admin);
    }

    /**
     * Gives a member the admin or member role. Admins only, active workspace only. Demoting the
     * last admin, oneself included, is refused; giving someone the role they have changes nothing.
     */
    @Transactional
    public WorkspaceMemberResponse changeRole(User user, Long workspaceId, Long userId, Role role) {
        if (role == null) {
            throw new BadRequestException("A role is required.");
        }
        workspaceRepository.lockById(workspaceId);
        workspaceAccess.requireActiveAdmin(workspaceId, user);
        WorkspaceMember target = requireTarget(workspaceId, userId);
        if (target.isAdmin() && role != Role.WORKSPACE_ADMIN) {
            requireAnotherAdmin(workspaceId);
        }
        target.setRole(role);
        return WorkspaceMemberResponse.from(workspaceMemberRepository.save(target));
    }

    /**
     * Removes a member from the workspace: an admin may remove anyone, a member only themselves
     * (leaving). The person also leaves every team and project of this workspace, and nothing in
     * other workspaces changes. Refused for the last admin, and for the only Project Manager of a
     * project, who must hand that role over first.
     */
    @Transactional
    public void removeMember(User user, Long workspaceId, Long userId) {
        workspaceRepository.lockById(workspaceId);
        WorkspaceMember caller = workspaceAccess.requireActiveMember(workspaceId, user);
        boolean leaving = user.getId().equals(userId);
        if (!leaving && !caller.isAdmin()) {
            throw new AccessDeniedException("Workspace admin role required.");
        }
        WorkspaceMember target = leaving ? caller : requireTarget(workspaceId, userId);
        if (target.isAdmin()) {
            requireAnotherAdmin(workspaceId);
        }
        projectRepository.lockAllWhereMemberHasRole(workspaceId, userId, ProjectRole.PROJECT_MANAGER);
        List<Project> onlyManagerOf = projectMemberRepository
                .findProjectsWhereOnlyHolder(workspaceId, userId, ProjectRole.PROJECT_MANAGER);
        if (!onlyManagerOf.isEmpty()) {
            throw new LastProjectManagerException("A project must have at least one Project Manager. "
                    + "Assign another one first in: "
                    + onlyManagerOf.stream().map(Project::getName).collect(Collectors.joining(", ")) + ".");
        }
        projectMemberRepository.deleteByWorkspaceIdAndUserId(workspaceId, userId);
        teamMemberRepository.deleteByWorkspaceIdAndUserId(workspaceId, userId);
        workspaceMemberRepository.delete(target);
    }

    private WorkspaceMember requireTarget(Long workspaceId, Long userId) {
        return workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Member not found."));
    }

    /** Callers hold the workspace lock, so the count cannot change before they commit. */
    private void requireAnotherAdmin(Long workspaceId) {
        if (workspaceMemberRepository.countByWorkspaceIdAndRole(workspaceId, Role.WORKSPACE_ADMIN) <= 1) {
            throw new LastWorkspaceAdminException("A workspace must have at least one admin.");
        }
    }

    /**
     * Deletes an archived workspace for good. Everything inside it goes with it through the
     * database's ON DELETE CASCADE, so every table under a workspace must declare
     * {@code @OnDelete(action = OnDeleteAction.CASCADE)}.
     */
    @Transactional
    public void delete(User user, Long workspaceId) {
        Workspace workspace = workspaceAccess.requireAdmin(workspaceId, user).getWorkspace();
        if (!workspace.isArchived()) {
            throw new WorkspaceNotArchivedException("Workspace must be archived before deletion.");
        }
        workspaceRepository.delete(workspace);
    }
}