package team.sprintory_backend.workspace.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.dto.CreateWorkspaceRequest;
import team.sprintory_backend.workspace.dto.UpdateWorkspaceRequest;
import team.sprintory_backend.workspace.dto.WorkspaceMemberResponse;
import team.sprintory_backend.workspace.dto.WorkspaceResponse;
import team.sprintory_backend.workspace.exceptions.WorkspaceNotArchivedException;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;
import team.sprintory_backend.workspace.repositories.WorkspaceRepository;

import java.time.Instant;
import java.util.List;

/**
 * R02 workspace logic: create, read, rename, archive, restore and permanently delete.
 * Access rules are in {@link WorkspaceAccess}.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceAccess workspaceAccess;
    private final SlugGenerator slugGenerator;

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

    public List<WorkspaceMemberResponse> listMembers(User user, Long workspaceId) {
        workspaceAccess.requireMember(workspaceId, user);
        return workspaceMemberRepository.findAllByWorkspaceId(workspaceId).stream()
                .map(WorkspaceMemberResponse::from)
                .toList();
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