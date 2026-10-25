package team.sprintory_backend.project.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.security.access.AccessDeniedException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectMember;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.exceptions.WorkspaceArchivedException;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;
import team.sprintory_backend.workspace.services.WorkspaceAccess;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static team.sprintory_backend.project.models.ProjectPermission.*;

/** Pure tests of the project access rules: repositories are mocked, WorkspaceAccess is real. */
class ProjectAccessTest {

    private static final Long WORKSPACE_ID = 3L;
    private static final Long PROJECT_ID = 21L;

    private ProjectMemberRepository projectMemberRepository;
    private WorkspaceMemberRepository workspaceMemberRepository;
    private ProjectAccess access;

    private Workspace workspace;
    private Project project;
    private final User grace = User.builder().id(8L).email("grace@example.com").build();

    @BeforeEach
    void setUp() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        projectMemberRepository = mock(ProjectMemberRepository.class);
        workspaceMemberRepository = mock(WorkspaceMemberRepository.class);
        access = new ProjectAccess(projectRepository, projectMemberRepository,
                new WorkspaceAccess(workspaceMemberRepository));

        workspace = Workspace.builder().id(WORKSPACE_ID).name("Acme").slug("acme").build();
        project = Project.builder().id(PROJECT_ID).workspace(workspace).name("Mobile App")
                .template(ProjectTemplate.SCRUM).build();
        when(projectRepository.findWithWorkspaceById(anyLong())).thenReturn(Optional.empty());
        when(projectRepository.findWithWorkspaceById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(workspaceMemberRepository.findByWorkspaceIdAndUserId(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(projectMemberRepository.findWithRolesByProjectIdAndUserId(anyLong(), anyLong()))
                .thenReturn(Optional.empty());
    }

    // --- visibility (404) ---

    @Test
    void aMissingProjectIsNotFound() {
        givenWorkspaceRole(Role.WORKSPACE_ADMIN);

        assertProjectNotFound(() -> access.requireVisible(99L, grace));
    }

    @Test
    void someoneOutsideTheWorkspaceGetsNotFound() {
        assertProjectNotFound(() -> access.requireVisible(PROJECT_ID, grace));
    }

    @Test
    void aWorkspaceMemberWhoIsNotInTheProjectGetsNotFoundEvenWhenAskingForAPermission() {
        givenWorkspaceRole(Role.WORKSPACE_MEMBER);

        assertProjectNotFound(() -> access.requireVisible(PROJECT_ID, grace));
        assertProjectNotFound(() -> access.require(PROJECT_ID, grace, EDIT_TASKS));
    }

    // --- permissions ---

    @Test
    void aProjectMemberGetsThePermissionsOfTheirRoles() {
        givenWorkspaceRole(Role.WORKSPACE_MEMBER);
        ProjectMember row = givenInProject(ProjectRole.PRODUCT_OWNER, ProjectRole.SCRUM_MASTER);

        ProjectAccessResult result = access.requireVisible(PROJECT_ID, grace);

        assertSame(project, result.project());
        assertSame(row, result.projectMember());
        assertEquals(ProjectRole.permissionsOf(Set.of(ProjectRole.PRODUCT_OWNER, ProjectRole.SCRUM_MASTER)),
                result.permissions());
    }

    @Test
    void anAdminWhoIsNotInTheProjectGetsTheAdminSetButNotTheWork() {
        givenWorkspaceRole(Role.WORKSPACE_ADMIN);

        ProjectAccessResult result = access.requireVisible(PROJECT_ID, grace);

        assertNull(result.projectMember());
        assertEquals(ProjectPermission.WORKSPACE_ADMIN, result.permissions());
        assertFalse(result.can(VIEW_WORK));
    }

    @Test
    void anAdminWhoIsAlsoInTheProjectGetsBothCombined() {
        givenWorkspaceRole(Role.WORKSPACE_ADMIN);
        givenInProject(ProjectRole.PRODUCT_OWNER);

        Set<ProjectPermission> expected = EnumSet.copyOf(ProjectRole.PRODUCT_OWNER.permissions());
        expected.addAll(ProjectPermission.WORKSPACE_ADMIN);
        assertEquals(expected, access.requireVisible(PROJECT_ID, grace).permissions());
    }

    @Test
    void requireRefusesAMissingPermissionWithForbidden() {
        givenWorkspaceRole(Role.WORKSPACE_MEMBER);
        givenInProject(ProjectRole.MEMBER);

        assertThrows(AccessDeniedException.class, () -> access.require(PROJECT_ID, grace, MANAGE_SPRINTS));
        assertDoesNotThrow(() -> access.require(PROJECT_ID, grace, EDIT_TASKS));
    }

    @Test
    void aViewerCannotEditTasks() {
        givenWorkspaceRole(Role.WORKSPACE_MEMBER);
        givenInProject(ProjectRole.VIEWER);

        assertThrows(AccessDeniedException.class, () -> access.require(PROJECT_ID, grace, EDIT_TASKS));
    }

    @Test
    void permissionsCannotBeChangedByTheCaller() {
        givenWorkspaceRole(Role.WORKSPACE_MEMBER);
        givenInProject(ProjectRole.VIEWER);

        Set<ProjectPermission> permissions = access.requireVisible(PROJECT_ID, grace).permissions();
        assertThrows(UnsupportedOperationException.class, () -> permissions.add(MANAGE_ROLES));
    }

    // --- archived workspace (409) ---

    @Test
    void requireActiveRefusesAnArchivedWorkspace() {
        givenWorkspaceRole(Role.WORKSPACE_MEMBER);
        givenInProject(ProjectRole.PROJECT_MANAGER);
        workspace.setArchivedAt(Instant.parse("2026-10-20T10:00:00Z"));

        assertThrows(WorkspaceArchivedException.class,
                () -> access.requireActive(PROJECT_ID, grace, MANAGE_MEMBERS));
        assertDoesNotThrow(() -> access.require(PROJECT_ID, grace, MANAGE_MEMBERS), "reads still work");
    }

    @Test
    void requireActiveChecksThePermissionBeforeTheArchive() {
        givenWorkspaceRole(Role.WORKSPACE_MEMBER);
        givenInProject(ProjectRole.MEMBER);
        workspace.setArchivedAt(Instant.parse("2026-10-20T10:00:00Z"));

        assertThrows(AccessDeniedException.class,
                () -> access.requireActive(PROJECT_ID, grace, MANAGE_MEMBERS));
    }

    // --- fixtures ---

    private void givenWorkspaceRole(Role role) {
        when(workspaceMemberRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, grace.getId()))
                .thenReturn(Optional.of(WorkspaceMember.builder().workspace(workspace).user(grace).role(role).build()));
    }

    private ProjectMember givenInProject(ProjectRole... roles) {
        ProjectMember row = ProjectMember.builder().project(project).user(grace)
                .roles(new HashSet<>(Set.of(roles))).build();
        when(projectMemberRepository.findWithRolesByProjectIdAndUserId(PROJECT_ID, grace.getId()))
                .thenReturn(Optional.of(row));
        return row;
    }

    private static void assertProjectNotFound(Executable call) {
        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class, call);
        assertEquals("Project not found.", e.getMessage());
    }
}