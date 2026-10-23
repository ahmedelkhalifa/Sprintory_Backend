package team.sprintory_backend.project.services;

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
import team.sprintory_backend.project.dto.AddProjectMembersRequest;
import team.sprintory_backend.project.dto.AddTeamToProjectRequest;
import team.sprintory_backend.project.dto.CreateProjectRequest;
import team.sprintory_backend.project.dto.ProjectMemberResponse;
import team.sprintory_backend.project.dto.ProjectResponse;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectMember;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.team.models.Team;
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
 * R02 project membership, with a minimal project (name only; story 8 adds key and template).
 * A project is visible to its members and to the workspace's admins, who manage membership;
 * anyone else gets the same 404 as a missing project (R04).
 */
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final WorkspaceAccess workspaceAccess;

    /** Any member of an active workspace can create a project, and becomes its first member. */
    @Transactional
    public ProjectResponse create(User user, Long workspaceId, CreateProjectRequest request) {
        Workspace workspace = workspaceAccess.requireActiveMember(workspaceId, user).getWorkspace();
        Project project = projectRepository.save(Project.builder()
                .workspace(workspace)
                .name(request.name().trim())
                .build());
        projectMemberRepository.save(ProjectMember.builder().project(project).user(user).build());
        return ProjectResponse.from(project);
    }

    /** Admins see every project of the workspace, members only the projects they belong to. */
    public PageResponse<ProjectResponse> list(User user, Long workspaceId, String q, Pageable pageable) {
        WorkspaceMember membership = workspaceAccess.requireMember(workspaceId, user);
        String pattern = Pageables.containsPattern(q);
        Pageable page = Pageables.withoutSort(pageable);
        Page<Project> projects = membership.isAdmin()
                ? projectRepository.searchByWorkspaceId(workspaceId, pattern, page)
                : projectRepository.searchByWorkspaceIdAndMemberId(workspaceId, user.getId(), pattern, page);
        return PageResponse.from(projects.map(ProjectResponse::from));
    }

    public ProjectResponse get(User user, Long projectId) {
        return ProjectResponse.from(requireVisible(projectId, user).project());
    }

    public PageResponse<ProjectMemberResponse> listMembers(User user, Long projectId, String q, Pageable pageable) {
        requireVisible(projectId, user);
        return PageResponse.from(projectMemberRepository
                .searchByProjectId(projectId, Pageables.containsPattern(q), Pageables.withoutSort(pageable))
                .map(ProjectMemberResponse::from));
    }

    /**
     * Adds workspace members to the project. All or nothing: if any id is not a member of the
     * workspace, nobody is added (400). People already in the project are skipped.
     */
    @Transactional
    public void addMembers(User user, Long projectId, AddProjectMembersRequest request) {
        Project project = requireActiveAdmin(projectId, user);
        Set<Long> userIds = new LinkedHashSet<>(request.userIds());
        List<WorkspaceMember> members = workspaceMemberRepository
                .findAllByWorkspaceIdAndUserIdIn(project.getWorkspace().getId(), userIds);
        if (members.size() != userIds.size()) {
            throw new BadRequestException("Some users are not members of this workspace.");
        }
        members.forEach(member -> addIfAbsent(project, member.getUser()));
    }

    /**
     * Copies the team's current members into the project, once: people who join the team later
     * are not added, and nobody is removed when they leave it. A team of another workspace gets
     * the same 400 as a missing one.
     */
    @Transactional
    public void addTeam(User user, Long projectId, AddTeamToProjectRequest request) {
        Project project = requireActiveAdmin(projectId, user);
        Team team = teamRepository.findById(request.teamId())
                .filter(found -> found.getWorkspace().getId().equals(project.getWorkspace().getId()))
                .orElseThrow(() -> new BadRequestException("Team not found in this workspace."));
        teamMemberRepository.findUsersByTeamId(team.getId())
                .forEach(teamMember -> addIfAbsent(project, teamMember));
    }

    /** Removing someone who is not in the project changes nothing and still succeeds. */
    @Transactional
    public void removeMember(User user, Long projectId, Long userId) {
        Project project = requireActiveAdmin(projectId, user);
        projectMemberRepository.deleteByProjectIdAndUserId(project.getId(), userId);
    }

    private void addIfAbsent(Project project, User user) {
        if (!projectMemberRepository.existsByProjectIdAndUserId(project.getId(), user.getId())) {
            projectMemberRepository.save(ProjectMember.builder().project(project).user(user).build());
        }
    }

    private record ProjectAccess(Project project, WorkspaceMember membership) {}

    /** 404 unless the caller is an admin of the project's workspace or a member of the project. */
    private ProjectAccess requireVisible(Long projectId, User user) {
        Project project = projectRepository.findWithWorkspaceById(projectId)
                .orElseThrow(ProjectService::projectNotFound);
        WorkspaceMember membership = workspaceAccess.findMember(project.getWorkspace().getId(), user)
                .orElseThrow(ProjectService::projectNotFound);
        if (!membership.isAdmin() && !projectMemberRepository.existsByProjectIdAndUserId(projectId, user.getId())) {
            throw projectNotFound();
        }
        return new ProjectAccess(project, membership);
    }

    /** A project member who is not an admin can see the project, so they get 403 rather than 404. */
    private Project requireActiveAdmin(Long projectId, User user) {
        ProjectAccess access = requireVisible(projectId, user);
        if (!access.membership().isAdmin()) {
            throw new AccessDeniedException("Workspace admin role required.");
        }
        WorkspaceAccess.requireNotArchived(access.membership());
        return access.project();
    }

    private static ResourceNotFoundException projectNotFound() {
        return new ResourceNotFoundException("Project not found.");
    }
}