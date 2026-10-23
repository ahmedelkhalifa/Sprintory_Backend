package team.sprintory_backend.team.services;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.common.Pageables;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.team.dto.AddTeamMembersRequest;
import team.sprintory_backend.team.dto.CreateTeamRequest;
import team.sprintory_backend.team.dto.TeamMemberResponse;
import team.sprintory_backend.team.dto.TeamResponse;
import team.sprintory_backend.team.dto.UpdateTeamRequest;
import team.sprintory_backend.team.models.Team;
import team.sprintory_backend.team.models.TeamMember;
import team.sprintory_backend.team.repositories.TeamMemberRepository;
import team.sprintory_backend.team.repositories.TeamRepository;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;
import team.sprintory_backend.workspace.services.WorkspaceAccess;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * R02 teams. A team is visible to the workspace's admins and to its own members; anyone else
 * gets the same 404 as a missing team. Only an admin of an active workspace can change one.
 */
@Service
@RequiredArgsConstructor
public class TeamService {

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceAccess workspaceAccess;

    @Transactional
    public TeamResponse create(User user, Long workspaceId, CreateTeamRequest request) {
        Workspace workspace = workspaceAccess.requireActiveAdmin(workspaceId, user).getWorkspace();
        // Flush now: a duplicate name in this workspace fails here with 409.
        Team team = teamRepository.saveAndFlush(Team.builder()
                .workspace(workspace)
                .name(request.name().trim())
                .build());
        return TeamResponse.from(team);
    }

    /** Admins see every team of the workspace, members only the teams they belong to. */
    public PageResponse<TeamResponse> list(User user, Long workspaceId, String q, Pageable pageable) {
        WorkspaceMember membership = workspaceAccess.requireMember(workspaceId, user);
        String pattern = Pageables.containsPattern(q);
        Pageable page = Pageables.withoutSort(pageable);
        Page<Team> teams = membership.isAdmin()
                ? teamRepository.searchByWorkspaceId(workspaceId, pattern, page)
                : teamRepository.searchByWorkspaceIdAndMemberId(workspaceId, user.getId(), pattern, page);
        return PageResponse.from(teams.map(TeamResponse::from));
    }

    public TeamResponse get(User user, Long teamId) {
        return TeamResponse.from(requireVisible(teamId, user).team());
    }

    public PageResponse<TeamMemberResponse> listMembers(User user, Long teamId, String q, Pageable pageable) {
        requireVisible(teamId, user);
        return PageResponse.from(teamMemberRepository
                .searchByTeamId(teamId, Pageables.containsPattern(q), Pageables.withoutSort(pageable))
                .map(TeamMemberResponse::from));
    }

    @Transactional
    public TeamResponse rename(User user, Long teamId, UpdateTeamRequest request) {
        Team team = requireActiveAdmin(teamId, user);
        team.setName(request.name().trim());
        // Flush now: a duplicate name in this workspace fails here with 409.
        teamRepository.saveAndFlush(team);
        return TeamResponse.from(team);
    }

    /** Removes the team and its member rows; nobody loses workspace or project access. */
    @Transactional
    public void delete(User user, Long teamId) {
        teamRepository.delete(requireActiveAdmin(teamId, user));
    }

    /**
     * Adds workspace members to the team. All or nothing: if any id is not a member of the
     * workspace, nobody is added (400). People already in the team are skipped.
     */
    @Transactional
    public void addMembers(User user, Long teamId, AddTeamMembersRequest request) {
        Team team = requireActiveAdmin(teamId, user);
        Set<Long> userIds = new LinkedHashSet<>(request.userIds());
        List<WorkspaceMember> members = workspaceMemberRepository
                .findAllByWorkspaceIdAndUserIdIn(team.getWorkspace().getId(), userIds);
        if (members.size() != userIds.size()) {
            throw new BadRequestException("Some users are not members of this workspace.");
        }
        for (WorkspaceMember member : members) {
            if (!teamMemberRepository.existsByTeamIdAndUserId(team.getId(), member.getUser().getId())) {
                teamMemberRepository.save(TeamMember.builder().team(team).user(member.getUser()).build());
            }
        }
    }

    /** Removing someone who is not in the team changes nothing and still succeeds. */
    @Transactional
    public void removeMember(User user, Long teamId, Long userId) {
        Team team = requireActiveAdmin(teamId, user);
        teamMemberRepository.deleteByTeamIdAndUserId(team.getId(), userId);
    }

    private record TeamAccess(Team team, WorkspaceMember membership) {}

    /** 404 unless the caller is an admin of the team's workspace or a member of the team. */
    private TeamAccess requireVisible(Long teamId, User user) {
        Team team = teamRepository.findWithWorkspaceById(teamId).orElseThrow(TeamService::teamNotFound);
        WorkspaceMember membership = workspaceAccess.findMember(team.getWorkspace().getId(), user)
                .orElseThrow(TeamService::teamNotFound);
        if (!membership.isAdmin() && !teamMemberRepository.existsByTeamIdAndUserId(teamId, user.getId())) {
            throw teamNotFound();
        }
        return new TeamAccess(team, membership);
    }

    /** A team member who is not an admin can see the team, so they get 403 rather than 404. */
    private Team requireActiveAdmin(Long teamId, User user) {
        TeamAccess access = requireVisible(teamId, user);
        if (!access.membership().isAdmin()) {
            throw new AccessDeniedException("Workspace admin role required.");
        }
        WorkspaceAccess.requireNotArchived(access.membership());
        return access.team();
    }

    private static ResourceNotFoundException teamNotFound() {
        return new ResourceNotFoundException("Team not found.");
    }
}