package team.sprintory_backend.workspace.services;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.exceptions.WorkspaceArchivedException;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;

/**
 * Workspace-level access checks shared by workspaces, teams and projects. A non-member gets the
 * same 404 as a missing workspace (docs/api-conventions.md §5); a member without the admin role
 * gets 403.
 *
 * <p>Use the {@code Active} variants for anything that changes data: they also refuse an
 * archived workspace with 409. The plain variants are for reads, and for archive, restore and
 * delete, which must work on an archived workspace.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceAccess {

    private final WorkspaceMemberRepository workspaceMemberRepository;

    /** Returns the caller's membership, with its workspace loaded. */
    public WorkspaceMember requireMember(Long workspaceId, User user) {
        return workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Workspace not found."));
    }

    public WorkspaceMember requireAdmin(Long workspaceId, User user) {
        WorkspaceMember member = requireMember(workspaceId, user);
        if (!member.isAdmin()) {
            throw new AccessDeniedException("Workspace admin role required.");
        }
        return member;
    }

    public WorkspaceMember requireActiveMember(Long workspaceId, User user) {
        return requireNotArchived(requireMember(workspaceId, user));
    }

    public WorkspaceMember requireActiveAdmin(Long workspaceId, User user) {
        return requireNotArchived(requireAdmin(workspaceId, user));
    }

    private static WorkspaceMember requireNotArchived(WorkspaceMember member) {
        if (member.getWorkspace().isArchived()) {
            throw new WorkspaceArchivedException("Workspace is archived.");
        }
        return member;
    }
}