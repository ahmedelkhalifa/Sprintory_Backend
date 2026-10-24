package team.sprintory_backend.project.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.dto.AddProjectMembersRequest;
import team.sprintory_backend.project.dto.AddTeamToProjectRequest;
import team.sprintory_backend.project.dto.CreateProjectRequest;
import team.sprintory_backend.project.dto.ProjectMemberResponse;
import team.sprintory_backend.project.dto.ProjectResponse;
import team.sprintory_backend.project.exceptions.InvalidProjectRolesException;
import team.sprintory_backend.project.exceptions.LastProjectManagerException;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectMember;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.team.models.Team;
import team.sprintory_backend.team.repositories.TeamMemberRepository;
import team.sprintory_backend.team.repositories.TeamRepository;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.exceptions.WorkspaceArchivedException;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;
import team.sprintory_backend.workspace.services.WorkspaceAccess;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Pure service tests: every repository is mocked, so no database or Spring context.
 * WorkspaceAccess is real, so the workspace part of every access rule is tested here too.
 */
class ProjectServiceTest {

    private static final Long WORKSPACE_ID = 3L;
    private static final Long PROJECT_ID = 21L;
    private static final Long TEAM_ID = 4L;

    private ProjectRepository projectRepository;
    private ProjectMemberRepository projectMemberRepository;
    private WorkspaceMemberRepository workspaceMemberRepository;
    private TeamRepository teamRepository;
    private TeamMemberRepository teamMemberRepository;
    private ProjectService service;

    private Workspace workspace;
    private Project project;
    private final User admin = user(7L, "ada@example.com");
    private final User member = user(8L, "grace@example.com");

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectRepository.class);
        projectMemberRepository = mock(ProjectMemberRepository.class);
        workspaceMemberRepository = mock(WorkspaceMemberRepository.class);
        teamRepository = mock(TeamRepository.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        WorkspaceAccess workspaceAccess = new WorkspaceAccess(workspaceMemberRepository);
        service = new ProjectService(projectRepository, projectMemberRepository, workspaceMemberRepository,
                teamRepository, teamMemberRepository, workspaceAccess,
                new ProjectAccess(projectRepository, projectMemberRepository, workspaceAccess));

        workspace = Workspace.builder().id(WORKSPACE_ID).name("Acme").slug("acme").build();
        project = Project.builder().id(PROJECT_ID).workspace(workspace).name("Mobile App")
                .template(ProjectTemplate.SCRUM).build();
        when(workspaceMemberRepository.findByWorkspaceIdAndUserId(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(projectRepository.findWithWorkspaceById(anyLong())).thenReturn(Optional.empty());
        when(projectRepository.findWithWorkspaceById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(projectMemberRepository.existsByProjectIdAndUserId(anyLong(), anyLong())).thenReturn(false);
        when(teamRepository.findById(anyLong())).thenReturn(Optional.empty());
    }

    // --- create ---

    @Test
    void createRefusesNonMembersWithWorkspaceNotFound() {
        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class,
                () -> service.create(member, WORKSPACE_ID, new CreateProjectRequest("Mobile App", ProjectTemplate.SCRUM)));
        assertEquals("Workspace not found.", e.getMessage());
    }

    @Test
    void createRefusesAnArchivedWorkspace() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);
        archiveWorkspace();

        assertThrows(WorkspaceArchivedException.class,
                () -> service.create(member, WORKSPACE_ID, new CreateProjectRequest("Mobile App", ProjectTemplate.SCRUM)));
        verify(projectRepository, never()).save(any());
    }

    @Test
    void createLetsAPlainMemberCreateAndMakesThemItsProjectManager() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);
        when(projectRepository.save(any(Project.class))).thenAnswer(call -> {
            Project saved = call.getArgument(0);
            saved.setId(PROJECT_ID);
            return saved;
        });

        ProjectResponse response = service.create(member, WORKSPACE_ID,
                new CreateProjectRequest("  Mobile App ", ProjectTemplate.KANBAN));

        ArgumentCaptor<Project> saved = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(saved.capture());
        assertEquals(ProjectTemplate.KANBAN, saved.getValue().getTemplate());
        ArgumentCaptor<ProjectMember> first = ArgumentCaptor.forClass(ProjectMember.class);
        verify(projectMemberRepository).save(first.capture());
        assertSame(member, first.getValue().getUser());
        assertEquals(PROJECT_ID, first.getValue().getProject().getId());
        assertEquals(Set.of(ProjectRole.PROJECT_MANAGER), first.getValue().getRoles());
        assertEquals(new ProjectResponse(PROJECT_ID, WORKSPACE_ID, "Mobile App"), response);
    }

    // --- list ---

    @Test
    void listGivesAdminsEveryProject() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        when(projectRepository.searchByWorkspaceId(anyLong(), anyString(), any()))
                .thenReturn(new PageImpl<>(List.of(project), PageRequest.of(0, 20), 1));

        PageResponse<ProjectResponse> page = service.list(admin, WORKSPACE_ID, "Mob",
                PageRequest.of(0, 20, Sort.by("name")));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(projectRepository).searchByWorkspaceId(eq(WORKSPACE_ID), eq("%mob%"), pageable.capture());
        assertTrue(pageable.getValue().getSort().isUnsorted());
        verify(projectRepository, never()).searchByWorkspaceIdAndMemberId(anyLong(), anyLong(), anyString(), any());
        assertEquals(List.of(new ProjectResponse(PROJECT_ID, WORKSPACE_ID, "Mobile App")), page.content());
    }

    @Test
    void listGivesPlainMembersOnlyTheirOwnProjects() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);
        when(projectRepository.searchByWorkspaceIdAndMemberId(anyLong(), anyLong(), anyString(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.list(member, WORKSPACE_ID, null, PageRequest.of(0, 20));

        verify(projectRepository).searchByWorkspaceIdAndMemberId(eq(WORKSPACE_ID), eq(8L), eq("%"), any());
        verify(projectRepository, never()).searchByWorkspaceId(anyLong(), anyString(), any());
    }

    @Test
    void listRefusesNonMembers() {
        assertThrows(ResourceNotFoundException.class,
                () -> service.list(member, WORKSPACE_ID, null, PageRequest.of(0, 20)));
    }

    // --- visibility (get, listMembers) ---

    @Test
    void getRefusesAMissingProject() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);

        assertProjectNotFound(() -> service.get(admin, 99L));
    }

    @Test
    void getAnswersProjectNotFoundToSomeoneOutsideTheWorkspace() {
        assertProjectNotFound(() -> service.get(member, PROJECT_ID));
    }

    @Test
    void getAnswersProjectNotFoundToAMemberWhoIsNotInTheProject() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);

        assertProjectNotFound(() -> service.get(member, PROJECT_ID));
    }

    @Test
    void getAllowsAMemberOfTheProject() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);
        givenInProject(member);

        assertEquals("Mobile App", service.get(member, PROJECT_ID).name());
    }

    @Test
    void getAllowsAnAdminWhoIsNotInTheProject() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);

        assertEquals("Mobile App", service.get(admin, PROJECT_ID).name());
    }

    @Test
    void getWorksOnAnArchivedWorkspace() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);
        givenInProject(member);
        archiveWorkspace();

        assertEquals("Mobile App", service.get(member, PROJECT_ID).name());
    }

    @Test
    void listMembersSearchesTheProject() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);
        givenInProject(member);
        ProjectMember row = ProjectMember.builder().project(project).user(member).build();
        when(projectMemberRepository.searchByProjectId(anyLong(), anyString(), any()))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

        PageResponse<ProjectMemberResponse> page = service.listMembers(member, PROJECT_ID, "Grace",
                PageRequest.of(0, 20));

        verify(projectMemberRepository).searchByProjectId(eq(PROJECT_ID), eq("%grace%"), any());
        assertEquals(8L, page.content().get(0).userId());
    }

    @Test
    void listMembersRefusesWhoeverCannotSeeTheProject() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);

        assertProjectNotFound(() -> service.listMembers(member, PROJECT_ID, null, PageRequest.of(0, 20)));
        verify(projectMemberRepository, never()).searchByProjectId(anyLong(), anyString(), any());
    }

    // --- add people ---

    @Test
    void addMembersRefusesAProjectMemberWhoIsNotAnAdminWithForbidden() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);
        givenInProject(member);

        assertThrows(AccessDeniedException.class,
                () -> service.addMembers(member, PROJECT_ID, new AddProjectMembersRequest(List.of(12L))));
        verify(projectMemberRepository, never()).save(any());
    }

    @Test
    void addMembersLetsTheProjectManagerWhoIsNotAnAdminAddPeople() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);
        givenInProject(member, ProjectRole.PROJECT_MANAGER);
        User linus = user(12L, "linus@example.com");
        when(workspaceMemberRepository.findAllByWorkspaceIdAndUserIdIn(WORKSPACE_ID, Set.of(12L)))
                .thenReturn(List.of(membership(linus, Role.WORKSPACE_MEMBER)));

        service.addMembers(member, PROJECT_ID, new AddProjectMembersRequest(List.of(12L)));

        ArgumentCaptor<ProjectMember> saved = ArgumentCaptor.forClass(ProjectMember.class);
        verify(projectMemberRepository).save(saved.capture());
        assertSame(linus, saved.getValue().getUser());
    }

    @Test
    void addMembersRefusesAMemberWhoCannotSeeTheProjectWithNotFound() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);

        assertProjectNotFound(
                () -> service.addMembers(member, PROJECT_ID, new AddProjectMembersRequest(List.of(12L))));
    }

    @Test
    void addMembersRefusesAnArchivedWorkspace() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        archiveWorkspace();

        assertThrows(WorkspaceArchivedException.class,
                () -> service.addMembers(admin, PROJECT_ID, new AddProjectMembersRequest(List.of(8L))));
    }

    @Test
    void addMembersIsAllOrNothing() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        // Asked for 8 and 99; only 8 is in the workspace.
        when(workspaceMemberRepository.findAllByWorkspaceIdAndUserIdIn(eq(WORKSPACE_ID), any()))
                .thenReturn(List.of(membership(member, Role.WORKSPACE_MEMBER)));

        assertThrows(BadRequestException.class,
                () -> service.addMembers(admin, PROJECT_ID, new AddProjectMembersRequest(List.of(8L, 99L))));
        verify(projectMemberRepository, never()).save(any());
    }

    @Test
    void addMembersSkipsPeopleAlreadyInTheProjectAndCountsDuplicatesOnce() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        User linus = user(12L, "linus@example.com");
        when(workspaceMemberRepository.findAllByWorkspaceIdAndUserIdIn(WORKSPACE_ID, Set.of(8L, 12L)))
                .thenReturn(List.of(membership(member, Role.WORKSPACE_MEMBER), membership(linus, Role.WORKSPACE_MEMBER)));
        givenInProject(member);

        service.addMembers(admin, PROJECT_ID, new AddProjectMembersRequest(List.of(8L, 8L, 12L)));

        ArgumentCaptor<ProjectMember> saved = ArgumentCaptor.forClass(ProjectMember.class);
        verify(projectMemberRepository, times(1)).save(saved.capture());
        assertSame(linus, saved.getValue().getUser());
        assertSame(project, saved.getValue().getProject());
        assertEquals(Set.of(ProjectRole.MEMBER), saved.getValue().getRoles(), "the default role");
    }

    // --- add a team ---

    @Test
    void addTeamCopiesTheCurrentTeamMembersAndSkipsExistingOnes() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        givenTeam(workspace);
        User linus = user(12L, "linus@example.com");
        when(teamMemberRepository.findUsersByTeamId(TEAM_ID)).thenReturn(List.of(member, linus));
        givenInProject(member);

        service.addTeam(admin, PROJECT_ID, new AddTeamToProjectRequest(TEAM_ID));

        ArgumentCaptor<ProjectMember> saved = ArgumentCaptor.forClass(ProjectMember.class);
        verify(projectMemberRepository, times(1)).save(saved.capture());
        assertSame(linus, saved.getValue().getUser());
        assertEquals(Set.of(ProjectRole.MEMBER), saved.getValue().getRoles());
    }

    @Test
    void addTeamRefusesATeamOfAnotherWorkspaceLikeAMissingOne() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        givenTeam(Workspace.builder().id(99L).name("Other").slug("other").build());

        BadRequestException foreign = assertThrows(BadRequestException.class,
                () -> service.addTeam(admin, PROJECT_ID, new AddTeamToProjectRequest(TEAM_ID)));
        BadRequestException missing = assertThrows(BadRequestException.class,
                () -> service.addTeam(admin, PROJECT_ID, new AddTeamToProjectRequest(404L)));

        assertEquals(missing.getMessage(), foreign.getMessage());
        verify(teamMemberRepository, never()).findUsersByTeamId(anyLong());
        verify(projectMemberRepository, never()).save(any());
    }

    @Test
    void addTeamWithAnEmptyTeamChangesNothing() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        givenTeam(workspace);
        when(teamMemberRepository.findUsersByTeamId(TEAM_ID)).thenReturn(List.of());

        assertDoesNotThrow(() -> service.addTeam(admin, PROJECT_ID, new AddTeamToProjectRequest(TEAM_ID)));
        verify(projectMemberRepository, never()).save(any());
    }

    @Test
    void addTeamRefusesAProjectMemberWhoIsNotAnAdmin() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);
        givenInProject(member);

        assertThrows(AccessDeniedException.class,
                () -> service.addTeam(member, PROJECT_ID, new AddTeamToProjectRequest(TEAM_ID)));
        verify(teamRepository, never()).findById(anyLong());
    }

    // --- remove ---

    @Test
    void removeMemberDeletesTheRowEvenWhenThereIsNone() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        when(projectMemberRepository.deleteByProjectIdAndUserId(PROJECT_ID, 12L)).thenReturn(0);

        assertDoesNotThrow(() -> service.removeMember(admin, PROJECT_ID, 12L));
        verify(projectMemberRepository).deleteByProjectIdAndUserId(PROJECT_ID, 12L);
    }

    @Test
    void removeMemberRefusesAnArchivedWorkspace() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        archiveWorkspace();

        assertThrows(WorkspaceArchivedException.class, () -> service.removeMember(admin, PROJECT_ID, 12L));
        verify(projectMemberRepository, never()).deleteByProjectIdAndUserId(anyLong(), anyLong());
    }

    @Test
    void removeMemberRefusesTheLastProjectManager() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        givenInProject(member, ProjectRole.PROJECT_MANAGER);
        givenManagerCount(1);

        LastProjectManagerException e = assertThrows(LastProjectManagerException.class,
                () -> service.removeMember(admin, PROJECT_ID, member.getId()));
        assertEquals("A project must have at least one Project Manager.", e.getMessage());
        verify(projectMemberRepository, never()).deleteByProjectIdAndUserId(anyLong(), anyLong());
    }

    @Test
    void removeMemberAllowsRemovingAProjectManagerWhenAnotherRemains() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        givenInProject(member, ProjectRole.PROJECT_MANAGER);
        givenManagerCount(2);

        service.removeMember(admin, PROJECT_ID, member.getId());

        verify(projectMemberRepository).deleteByProjectIdAndUserId(PROJECT_ID, member.getId());
    }

    @Test
    void removeMemberLetsTheOnlyScrumMasterGo() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        givenInProject(member, ProjectRole.SCRUM_MASTER);

        service.removeMember(admin, PROJECT_ID, member.getId());

        verify(projectMemberRepository).deleteByProjectIdAndUserId(PROJECT_ID, member.getId());
        verify(projectMemberRepository, never()).countByProjectIdAndRole(anyLong(), any());
    }

    @Test
    void removeMemberLocksTheProjectFirst() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);

        service.removeMember(admin, PROJECT_ID, 12L);

        var order = inOrder(projectRepository, projectMemberRepository);
        order.verify(projectRepository).lockById(PROJECT_ID);
        order.verify(projectMemberRepository).deleteByProjectIdAndUserId(PROJECT_ID, 12L);
    }

    // --- set roles ---

    @Test
    void setRolesLetsTheProjectManagerReplaceTheRoles() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);
        givenInProject(member, ProjectRole.PROJECT_MANAGER);
        User linus = user(12L, "linus@example.com");
        givenInProject(linus, ProjectRole.MEMBER);
        when(projectMemberRepository.save(any(ProjectMember.class))).thenAnswer(call -> call.getArgument(0));

        ProjectMemberResponse response = service.setRoles(member, PROJECT_ID, 12L,
                Set.of(ProjectRole.PRODUCT_OWNER, ProjectRole.SCRUM_MASTER));

        ArgumentCaptor<ProjectMember> saved = ArgumentCaptor.forClass(ProjectMember.class);
        verify(projectMemberRepository).save(saved.capture());
        assertEquals(Set.of(ProjectRole.PRODUCT_OWNER, ProjectRole.SCRUM_MASTER), saved.getValue().getRoles());
        assertEquals(12L, response.userId());
    }

    @Test
    void setRolesLetsAnAdminWhoIsNotInTheProjectChangeRoles() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        givenInProject(member, ProjectRole.MEMBER);
        when(projectMemberRepository.save(any(ProjectMember.class))).thenAnswer(call -> call.getArgument(0));

        service.setRoles(admin, PROJECT_ID, member.getId(), Set.of(ProjectRole.VIEWER));

        verify(projectMemberRepository).save(any(ProjectMember.class));
    }

    @Test
    void setRolesRefusesSomeoneWhoIsNotTheProjectManagerWithForbidden() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);
        givenInProject(member, ProjectRole.PRODUCT_OWNER, ProjectRole.SCRUM_MASTER);

        assertThrows(AccessDeniedException.class,
                () -> service.setRoles(member, PROJECT_ID, member.getId(), Set.of(ProjectRole.PROJECT_MANAGER)));
        verify(projectMemberRepository, never()).save(any());
    }

    @Test
    void setRolesAnswersNotFoundForSomeoneOutsideTheProject() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);

        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class,
                () -> service.setRoles(admin, PROJECT_ID, 12L, Set.of(ProjectRole.MEMBER)));
        assertEquals("Project member not found.", e.getMessage());
    }

    @Test
    void setRolesRefusesAnEmptySet() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        givenInProject(member, ProjectRole.MEMBER);

        for (Set<ProjectRole> empty : Arrays.asList(Set.<ProjectRole>of(), null)) {
            assertInvalidRoles("A member must have at least one role.",
                    () -> service.setRoles(admin, PROJECT_ID, member.getId(), empty));
        }
    }

    @Test
    void setRolesRefusesViewerCombinedWithAnotherRole() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        givenInProject(member, ProjectRole.MEMBER);

        assertInvalidRoles("Viewer cannot be combined with other roles.",
                () -> service.setRoles(admin, PROJECT_ID, member.getId(),
                        Set.of(ProjectRole.VIEWER, ProjectRole.MEMBER)));
    }

    @Test
    void setRolesRefusesScrumRolesInAKanbanProject() {
        project.setTemplate(ProjectTemplate.KANBAN);
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        givenInProject(member, ProjectRole.MEMBER);

        assertInvalidRoles("Product Owner is not available in Kanban projects.",
                () -> service.setRoles(admin, PROJECT_ID, member.getId(), Set.of(ProjectRole.PRODUCT_OWNER)));
        assertInvalidRoles("Scrum Master is not available in Kanban projects.",
                () -> service.setRoles(admin, PROJECT_ID, member.getId(), Set.of(ProjectRole.SCRUM_MASTER)));
    }

    @Test
    void setRolesRefusesTakingTheRoleFromTheLastProjectManager() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);
        givenInProject(member, ProjectRole.PROJECT_MANAGER);
        givenManagerCount(1);

        LastProjectManagerException e = assertThrows(LastProjectManagerException.class,
                () -> service.setRoles(member, PROJECT_ID, member.getId(), Set.of(ProjectRole.MEMBER)));
        assertEquals("A project must have at least one Project Manager.", e.getMessage());
        verify(projectMemberRepository, never()).save(any());
    }

    @Test
    void setRolesLetsAProjectManagerStepDownWhenAnotherRemains() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);
        givenInProject(member, ProjectRole.PROJECT_MANAGER);
        givenManagerCount(2);
        when(projectMemberRepository.save(any(ProjectMember.class))).thenAnswer(call -> call.getArgument(0));

        service.setRoles(member, PROJECT_ID, member.getId(), Set.of(ProjectRole.SCRUM_MASTER));

        verify(projectMemberRepository).save(any(ProjectMember.class));
    }

    @Test
    void setRolesKeepingTheProjectManagerRoleNeedsNoSecondManager() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);
        givenInProject(member, ProjectRole.PROJECT_MANAGER);
        when(projectMemberRepository.save(any(ProjectMember.class))).thenAnswer(call -> call.getArgument(0));

        service.setRoles(member, PROJECT_ID, member.getId(),
                Set.of(ProjectRole.PROJECT_MANAGER, ProjectRole.PRODUCT_OWNER));

        verify(projectMemberRepository, never()).countByProjectIdAndRole(anyLong(), any());
    }

    @Test
    void setRolesRefusesAnArchivedWorkspace() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        givenInProject(member, ProjectRole.MEMBER);
        archiveWorkspace();

        assertThrows(WorkspaceArchivedException.class,
                () -> service.setRoles(admin, PROJECT_ID, member.getId(), Set.of(ProjectRole.VIEWER)));
    }

    @Test
    void setRolesLocksTheProjectFirst() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        givenInProject(member, ProjectRole.MEMBER);
        when(projectMemberRepository.save(any(ProjectMember.class))).thenAnswer(call -> call.getArgument(0));

        service.setRoles(admin, PROJECT_ID, member.getId(), Set.of(ProjectRole.VIEWER));

        var order = inOrder(projectRepository, projectMemberRepository);
        order.verify(projectRepository).lockById(PROJECT_ID);
        order.verify(projectMemberRepository).save(any(ProjectMember.class));
    }

    // --- fixtures ---

    private void givenManagerCount(long count) {
        when(projectMemberRepository.countByProjectIdAndRole(PROJECT_ID, ProjectRole.PROJECT_MANAGER))
                .thenReturn(count);
    }

    private static void assertInvalidRoles(String message, Executable call) {
        InvalidProjectRolesException e = assertThrows(InvalidProjectRolesException.class, call);
        assertEquals(message, e.getMessage());
    }

    private void givenWorkspaceRole(User user, Role role) {
        when(workspaceMemberRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, user.getId()))
                .thenReturn(Optional.of(membership(user, role)));
    }

    /** Puts the user in the project; with no roles given, as a plain MEMBER. */
    private void givenInProject(User user, ProjectRole... roles) {
        Set<ProjectRole> held = (roles.length == 0) ? Set.of(ProjectRole.MEMBER) : Set.of(roles);
        ProjectMember row = ProjectMember.builder().project(project).user(user).roles(new HashSet<>(held)).build();
        when(projectMemberRepository.findWithRolesByProjectIdAndUserId(PROJECT_ID, user.getId()))
                .thenReturn(Optional.of(row));
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, user.getId())).thenReturn(true);
    }

    private void givenTeam(Workspace teamWorkspace) {
        when(teamRepository.findById(TEAM_ID))
                .thenReturn(Optional.of(Team.builder().id(TEAM_ID).workspace(teamWorkspace).name("Backend").build()));
    }

    /** The project and every membership share one workspace object, so archiving it applies to all. */
    private void archiveWorkspace() {
        workspace.setArchivedAt(Instant.parse("2026-10-20T10:00:00Z"));
    }

    private WorkspaceMember membership(User user, Role role) {
        return WorkspaceMember.builder().workspace(workspace).user(user).role(role).build();
    }

    private static void assertProjectNotFound(Executable call) {
        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class, call);
        assertEquals("Project not found.", e.getMessage());
    }

    private static User user(Long id, String email) {
        return User.builder().id(id).firstName("User").lastName(String.valueOf(id)).email(email).build();
    }
}