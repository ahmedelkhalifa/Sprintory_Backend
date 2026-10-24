package team.sprintory_backend.project.services;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import team.sprintory_backend.project.exceptions.InvalidProjectRolesException;
import team.sprintory_backend.project.exceptions.LastProjectManagerException;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.models.ProjectRole;
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

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * R02 project membership, with a minimal project (story 8 adds the key and the board).
 * Access rules live in {@link ProjectAccess}: membership is managed by whoever holds
 * MANAGE_MEMBERS, i.e. the project's Project Managers and the workspace's admins.
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
    private final ProjectAccess projectAccess;

    /** Any member of an active workspace can create a project, and becomes its Project Manager. */
    @Transactional
    public ProjectResponse create(User user, Long workspaceId, CreateProjectRequest request) {
        WorkspaceMember creator = workspaceAccess.requireActiveMember(workspaceId, user);
        Workspace workspace = creator.getWorkspace();
        Project project = projectRepository.save(Project.builder()
                .workspace(workspace)
                .name(request.name().trim())
                .template(request.template())
                .build());
        projectMemberRepository.save(newMember(project, user, ProjectRole.PROJECT_MANAGER));
        return responseFor(project, Set.of(ProjectRole.PROJECT_MANAGER), creator.isAdmin());
    }

    /** Admins see every project of the workspace, members only the projects they belong to. */
    public PageResponse<ProjectResponse> list(User user, Long workspaceId, String q, Pageable pageable) {
        WorkspaceMember membership = workspaceAccess.requireMember(workspaceId, user);
        String pattern = Pageables.containsPattern(q);
        Pageable page = Pageables.withoutSort(pageable);
        Page<Project> projects = membership.isAdmin()
                ? projectRepository.searchByWorkspaceId(workspaceId, pattern, page)
                : projectRepository.searchByWorkspaceIdAndMemberId(workspaceId, user.getId(), pattern, page);
        // Load the caller's roles for the page in one query; workspace admins may not be project members.
        List<Long> ids = projects.getContent().stream().map(Project::getId).toList();
        Map<Long, Set<ProjectRole>> rolesByProject = new HashMap<>();
        if (!ids.isEmpty()) {
            projectMemberRepository.findWithRolesByProjectIdsAndUserId(ids, user.getId())
                    .forEach(member -> rolesByProject.put(member.getProject().getId(), member.getRoles()));
        }
        return PageResponse.from(projects.map(project -> responseFor(project,
                rolesByProject.getOrDefault(project.getId(), Set.of()), membership.isAdmin())));
    }

    public ProjectResponse get(User user, Long projectId) {
        ProjectAccessResult access = projectAccess.requireVisible(projectId, user);
        Set<ProjectRole> roles = access.projectMember() == null
                ? Set.of() : access.projectMember().getRoles();
        return ProjectResponse.from(access.project(), roles, access.permissions());
    }

    @Transactional(readOnly = true)
    public PageResponse<ProjectMemberResponse> listMembers(User user, Long projectId, String q, Pageable pageable) {
        projectAccess.requireVisible(projectId, user);
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
        Project project = projectAccess.requireActive(projectId, user, ProjectPermission.MANAGE_MEMBERS).project();
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
        Project project = projectAccess.requireActive(projectId, user, ProjectPermission.MANAGE_MEMBERS).project();
        Team team = teamRepository.findById(request.teamId())
                .filter(found -> found.getWorkspace().getId().equals(project.getWorkspace().getId()))
                .orElseThrow(() -> new BadRequestException("Team not found in this workspace."));
        teamMemberRepository.findUsersByTeamId(team.getId())
                .forEach(teamMember -> addIfAbsent(project, teamMember));
    }

    /**
     * Removing someone who is not in the project changes nothing and still succeeds. The last
     * Project Manager cannot be removed (409).
     */
    @Transactional
    public void removeMember(User user, Long projectId, Long userId) {
        projectRepository.lockById(projectId);
        Project project = projectAccess.requireActive(projectId, user, ProjectPermission.MANAGE_MEMBERS).project();
        projectMemberRepository.findWithRolesByProjectIdAndUserId(project.getId(), userId)
                .filter(target -> target.hasRole(ProjectRole.PROJECT_MANAGER))
                .ifPresent(target -> requireAnotherManager(project));
        projectMemberRepository.deleteByProjectIdAndUserId(project.getId(), userId);
    }

    /**
     * Replaces a member's project roles with {@code roles}, which covers assigning, changing and
     * removing roles in one call. The set must be non-empty, may hold Viewer only on its own, may
     * hold only roles the project's template offers, and must not take the role away from the
     * last Project Manager.
     */
    @Transactional
    public ProjectMemberResponse setRoles(User user, Long projectId, Long userId, Set<ProjectRole> roles) {
        projectRepository.lockById(projectId);
        Project project = projectAccess.requireActive(projectId, user, ProjectPermission.MANAGE_ROLES).project();
        ProjectMember target = projectMemberRepository.findWithRolesByProjectIdAndUserId(project.getId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Project member not found."));
        Set<ProjectRole> wanted = validRoles(project, roles);
        if (target.hasRole(ProjectRole.PROJECT_MANAGER) && !wanted.contains(ProjectRole.PROJECT_MANAGER)) {
            requireAnotherManager(project);
        }
        target.getRoles().clear();
        target.getRoles().addAll(wanted);
        return ProjectMemberResponse.from(projectMemberRepository.save(target));
    }

    private static ProjectResponse responseFor(Project project, Set<ProjectRole> roles, boolean workspaceAdmin) {
        Set<ProjectPermission> permissions = EnumSet.noneOf(ProjectPermission.class);
        permissions.addAll(ProjectRole.permissionsOf(roles));
        if (workspaceAdmin) {
            permissions.addAll(ProjectPermission.WORKSPACE_ADMIN);
        }
        return ProjectResponse.from(project, roles, permissions);
    }

    private void addIfAbsent(Project project, User user) {
        if (!projectMemberRepository.existsByProjectIdAndUserId(project.getId(), user.getId())) {
            projectMemberRepository.save(newMember(project, user, ProjectRole.MEMBER));
        }
    }

    private static ProjectMember newMember(Project project, User user, ProjectRole role) {
        return ProjectMember.builder().project(project).user(user).roles(new HashSet<>(Set.of(role))).build();
    }

    /** Callers hold the project lock, so the count cannot change before they commit. */
    private void requireAnotherManager(Project project) {
        if (projectMemberRepository.countByProjectIdAndRole(project.getId(), ProjectRole.PROJECT_MANAGER) <= 1) {
            throw new LastProjectManagerException("A project must have at least one Project Manager.");
        }
    }

    private static Set<ProjectRole> validRoles(Project project, Set<ProjectRole> roles) {
        Set<ProjectRole> wanted = EnumSet.noneOf(ProjectRole.class);
        if (roles != null) {
            roles.stream().filter(Objects::nonNull).forEach(wanted::add);
        }
        if (wanted.isEmpty()) {
            throw new InvalidProjectRolesException("A member must have at least one role.");
        }
        if (wanted.contains(ProjectRole.VIEWER) && wanted.size() > 1) {
            throw new InvalidProjectRolesException("Viewer cannot be combined with other roles.");
        }
        for (ProjectRole role : wanted) {
            if (!project.getTemplate().offers(role)) {
                throw new InvalidProjectRolesException(
                        label(role) + " is not available in " + label(project.getTemplate()) + " projects.");
            }
        }
        return wanted;
    }

    /** {@code SCRUM_MASTER} → "Scrum Master", for messages. */
    private static String label(Enum<?> value) {
        return Arrays.stream(value.name().split("_"))
                .map(word -> word.charAt(0) + word.substring(1).toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" "));
    }

}