package team.sprintory_backend.workspace.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.exceptions.LastProjectManagerException;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.team.repositories.TeamMemberRepository;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.dto.CreateWorkspaceRequest;
import team.sprintory_backend.workspace.dto.UpdateWorkspaceRequest;
import team.sprintory_backend.workspace.dto.WorkspaceMemberResponse;
import team.sprintory_backend.workspace.dto.WorkspaceResponse;
import team.sprintory_backend.workspace.exceptions.LastWorkspaceAdminException;
import team.sprintory_backend.workspace.exceptions.WorkspaceArchivedException;
import team.sprintory_backend.workspace.exceptions.WorkspaceNotArchivedException;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;
import team.sprintory_backend.workspace.repositories.WorkspaceRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Pure service tests: every repository is mocked, so no database or Spring context.
 * WorkspaceAccess is real, so the access rules (404 / 403 / 409) are tested here too.
 */
class WorkspaceServiceTest {

    private static final Long WORKSPACE_ID = 3L;

    private static final Long OTHER_ID = 8L;

    private WorkspaceRepository workspaceRepository;
    private WorkspaceMemberRepository workspaceMemberRepository;
    private SlugGenerator slugGenerator;
    private ProjectRepository projectRepository;
    private ProjectMemberRepository projectMemberRepository;
    private TeamMemberRepository teamMemberRepository;
    private TaskRepository taskRepository;
    private WorkspaceService service;
    private User user;
    private final User grace = User.builder().id(OTHER_ID).firstName("Grace").lastName("Hopper")
            .email("grace@example.com").build();

    @BeforeEach
    void setUp() {
        workspaceRepository = mock(WorkspaceRepository.class);
        workspaceMemberRepository = mock(WorkspaceMemberRepository.class);
        slugGenerator = mock(SlugGenerator.class);
        projectRepository = mock(ProjectRepository.class);
        projectMemberRepository = mock(ProjectMemberRepository.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        taskRepository = mock(TaskRepository.class);
        service = new WorkspaceService(workspaceRepository, workspaceMemberRepository,
                new WorkspaceAccess(workspaceMemberRepository), slugGenerator,
                projectRepository, projectMemberRepository, teamMemberRepository, taskRepository);
        user = User.builder().id(7L).firstName("Ada").lastName("Lovelace").email("ada@example.com").build();
        when(workspaceMemberRepository.findByWorkspaceIdAndUserId(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(workspaceMemberRepository.save(any(WorkspaceMember.class))).thenAnswer(call -> call.getArgument(0));
        when(projectMemberRepository.findProjectsWhereOnlyHolder(anyLong(), anyLong(), any())).thenReturn(List.of());
    }

    // --- create ---

    @Test
    void createSavesWorkspaceWithGeneratedSlugAndMakesCreatorAdmin() {
        when(slugGenerator.generate("Acme")).thenReturn("acme");
        when(workspaceRepository.saveAndFlush(any(Workspace.class))).thenAnswer(call -> {
            Workspace saved = call.getArgument(0);
            saved.setId(WORKSPACE_ID);
            return saved;
        });
        when(workspaceMemberRepository.save(any(WorkspaceMember.class))).thenAnswer(call -> call.getArgument(0));

        WorkspaceResponse response = service.create(user, new CreateWorkspaceRequest("  Acme "));

        ArgumentCaptor<WorkspaceMember> admin = ArgumentCaptor.forClass(WorkspaceMember.class);
        verify(workspaceMemberRepository).save(admin.capture());
        assertSame(user, admin.getValue().getUser());
        assertEquals(Role.WORKSPACE_ADMIN, admin.getValue().getRole());
        assertEquals(new WorkspaceResponse(WORKSPACE_ID, "Acme", "acme", Role.WORKSPACE_ADMIN, null), response);
    }

    // --- read ---

    @Test
    void listMineReturnsEveryMembershipWithItsRole() {
        Workspace other = workspace(false);
        other.setId(5L);
        when(workspaceMemberRepository.findAllByUserId(7L)).thenReturn(List.of(
                membership(workspace(false), Role.WORKSPACE_ADMIN),
                membership(other, Role.WORKSPACE_MEMBER)));

        List<WorkspaceResponse> mine = service.listMine(user);

        assertEquals(2, mine.size());
        assertEquals(Role.WORKSPACE_ADMIN, mine.get(0).role());
        assertEquals(Role.WORKSPACE_MEMBER, mine.get(1).role());
    }

    @Test
    void getRefusesNonMembersWithNotFound() {
        assertThrows(ResourceNotFoundException.class, () -> service.get(user, WORKSPACE_ID));
    }

    @Test
    void getReturnsTheWorkspaceWithTheCallersRole() {
        givenMembership(Role.WORKSPACE_MEMBER, false);

        WorkspaceResponse response = service.get(user, WORKSPACE_ID);

        assertEquals("acme", response.slug());
        assertEquals(Role.WORKSPACE_MEMBER, response.role());
    }

    @Test
    void getWorksOnAnArchivedWorkspace() {
        givenMembership(Role.WORKSPACE_MEMBER, true);

        assertNotNull(service.get(user, WORKSPACE_ID).archivedAt());
    }

    @Test
    void listMembersRefusesNonMembersWithoutQuerying() {
        assertThrows(ResourceNotFoundException.class,
                () -> service.listMembers(user, WORKSPACE_ID, null, PageRequest.of(0, 20)));
        verify(workspaceMemberRepository, never()).searchByWorkspaceId(anyLong(), anyString(), any());
    }

    @Test
    void listMembersSearchesCaseInsensitivelyAndIgnoresClientSort() {
        WorkspaceMember member = givenMembership(Role.WORKSPACE_MEMBER, false);
        when(workspaceMemberRepository.searchByWorkspaceId(eq(WORKSPACE_ID), anyString(), any()))
                .thenReturn(new PageImpl<>(List.of(member), PageRequest.of(1, 5), 6));

        PageResponse<WorkspaceMemberResponse> page = service.listMembers(user, WORKSPACE_ID, " Ada ",
                PageRequest.of(1, 5, Sort.by("email")));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(workspaceMemberRepository).searchByWorkspaceId(eq(WORKSPACE_ID), eq("%ada%"), pageable.capture());
        assertTrue(pageable.getValue().getSort().isUnsorted());
        assertEquals(1, pageable.getValue().getPageNumber());
        assertEquals(5, pageable.getValue().getPageSize());
        assertEquals(6, page.totalElements());
        assertEquals(2, page.totalPages());
        assertEquals(7L, page.content().get(0).userId());
    }

    @Test
    void listMembersWithoutSearchMatchesEveryone() {
        givenMembership(Role.WORKSPACE_MEMBER, false);
        when(workspaceMemberRepository.searchByWorkspaceId(anyLong(), anyString(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.listMembers(user, WORKSPACE_ID, "  ", PageRequest.of(0, 20));

        verify(workspaceMemberRepository).searchByWorkspaceId(eq(WORKSPACE_ID), eq("%"), any());
    }

    // --- rename ---

    @Test
    void renameRefusesPlainMembers() {
        givenMembership(Role.WORKSPACE_MEMBER, false);

        assertThrows(AccessDeniedException.class,
                () -> service.rename(user, WORKSPACE_ID, new UpdateWorkspaceRequest("New")));
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    void renameRefusesAnArchivedWorkspace() {
        givenMembership(Role.WORKSPACE_ADMIN, true);

        assertThrows(WorkspaceArchivedException.class,
                () -> service.rename(user, WORKSPACE_ID, new UpdateWorkspaceRequest("New")));
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    void renameTrimsTheNameAndKeepsTheSlug() {
        WorkspaceMember admin = givenMembership(Role.WORKSPACE_ADMIN, false);

        WorkspaceResponse response = service.rename(user, WORKSPACE_ID, new UpdateWorkspaceRequest(" Acme Labs "));

        verify(workspaceRepository).save(admin.getWorkspace());
        assertEquals("Acme Labs", response.name());
        assertEquals("acme", response.slug());
    }

    // --- archive / restore ---

    @Test
    void archiveRefusesPlainMembers() {
        givenMembership(Role.WORKSPACE_MEMBER, false);

        assertThrows(AccessDeniedException.class, () -> service.archive(user, WORKSPACE_ID));
    }

    @Test
    void archiveSetsArchivedAt() {
        givenMembership(Role.WORKSPACE_ADMIN, false);

        WorkspaceResponse response = service.archive(user, WORKSPACE_ID);

        assertNotNull(response.archivedAt());
        verify(workspaceRepository).save(any(Workspace.class));
    }

    @Test
    void archivingAnArchivedWorkspaceKeepsTheOriginalDate() {
        WorkspaceMember admin = givenMembership(Role.WORKSPACE_ADMIN, true);
        Instant original = admin.getWorkspace().getArchivedAt();

        WorkspaceResponse response = service.archive(user, WORKSPACE_ID);

        assertEquals(original, response.archivedAt());
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    void restoreClearsArchivedAt() {
        givenMembership(Role.WORKSPACE_ADMIN, true);

        WorkspaceResponse response = service.restore(user, WORKSPACE_ID);

        assertNull(response.archivedAt());
        verify(workspaceRepository).save(any(Workspace.class));
    }

    @Test
    void restoringAnActiveWorkspaceChangesNothing() {
        givenMembership(Role.WORKSPACE_ADMIN, false);

        service.restore(user, WORKSPACE_ID);

        verify(workspaceRepository, never()).save(any());
    }

    // --- delete ---

    @Test
    void deleteRefusesAnActiveWorkspace() {
        givenMembership(Role.WORKSPACE_ADMIN, false);

        assertThrows(WorkspaceNotArchivedException.class, () -> service.delete(user, WORKSPACE_ID));
        verify(workspaceRepository, never()).delete(any());
    }

    @Test
    void deleteRefusesPlainMembers() {
        givenMembership(Role.WORKSPACE_MEMBER, true);

        assertThrows(AccessDeniedException.class, () -> service.delete(user, WORKSPACE_ID));
        verify(workspaceRepository, never()).delete(any());
    }

    @Test
    void deleteRefusesNonMembersWithNotFound() {
        assertThrows(ResourceNotFoundException.class, () -> service.delete(user, WORKSPACE_ID));
    }

    @Test
    void deleteRemovesAnArchivedWorkspace() {
        WorkspaceMember admin = givenMembership(Role.WORKSPACE_ADMIN, true);

        service.delete(user, WORKSPACE_ID);

        verify(workspaceRepository).delete(admin.getWorkspace());
    }

    // --- change role ---

    @Test
    void changeRoleLetsAnAdminPromoteAMember() {
        WorkspaceMember admin = givenMembership(Role.WORKSPACE_ADMIN, false);
        WorkspaceMember other = givenOther(admin.getWorkspace(), Role.WORKSPACE_MEMBER);

        WorkspaceMemberResponse response = service.changeRole(user, WORKSPACE_ID, OTHER_ID, Role.WORKSPACE_ADMIN);

        assertEquals(Role.WORKSPACE_ADMIN, other.getRole());
        verify(workspaceMemberRepository).save(other);
        assertEquals(new WorkspaceMemberResponse(OTHER_ID, "Grace", "Hopper", "grace@example.com",
                Role.WORKSPACE_ADMIN), response);
    }

    @Test
    void changeRoleLetsAnAdminDemoteAnotherAdminWhenOneRemains() {
        WorkspaceMember admin = givenMembership(Role.WORKSPACE_ADMIN, false);
        WorkspaceMember other = givenOther(admin.getWorkspace(), Role.WORKSPACE_ADMIN);
        givenAdminCount(2);

        service.changeRole(user, WORKSPACE_ID, OTHER_ID, Role.WORKSPACE_MEMBER);

        assertEquals(Role.WORKSPACE_MEMBER, other.getRole());
    }

    @Test
    void changeRoleLetsAnAdminStepDownWhenAnotherAdminRemains() {
        WorkspaceMember admin = givenMembership(Role.WORKSPACE_ADMIN, false);
        givenAdminCount(2);

        service.changeRole(user, WORKSPACE_ID, 7L, Role.WORKSPACE_MEMBER);

        assertEquals(Role.WORKSPACE_MEMBER, admin.getRole());
    }

    @Test
    void changeRoleRefusesDemotingTheOnlyAdminThemselves() {
        WorkspaceMember admin = givenMembership(Role.WORKSPACE_ADMIN, false);
        givenAdminCount(1);

        LastWorkspaceAdminException e = assertThrows(LastWorkspaceAdminException.class,
                () -> service.changeRole(user, WORKSPACE_ID, 7L, Role.WORKSPACE_MEMBER));
        assertEquals("A workspace must have at least one admin.", e.getMessage());
        assertEquals(Role.WORKSPACE_ADMIN, admin.getRole());
        verify(workspaceMemberRepository, never()).save(any());
    }

    @Test
    void changeRoleToTheSameRoleNeedsNoSecondAdmin() {
        givenMembership(Role.WORKSPACE_ADMIN, false);

        assertDoesNotThrow(() -> service.changeRole(user, WORKSPACE_ID, 7L, Role.WORKSPACE_ADMIN));
        verify(workspaceMemberRepository, never()).countByWorkspaceIdAndRole(anyLong(), any());
    }

    @Test
    void changeRoleRefusesPlainMembers() {
        WorkspaceMember member = givenMembership(Role.WORKSPACE_MEMBER, false);
        givenOther(member.getWorkspace(), Role.WORKSPACE_MEMBER);

        assertThrows(AccessDeniedException.class,
                () -> service.changeRole(user, WORKSPACE_ID, OTHER_ID, Role.WORKSPACE_ADMIN));
        verify(workspaceMemberRepository, never()).save(any());
    }

    @Test
    void changeRoleRefusesAnArchivedWorkspace() {
        givenMembership(Role.WORKSPACE_ADMIN, true);

        assertThrows(WorkspaceArchivedException.class,
                () -> service.changeRole(user, WORKSPACE_ID, OTHER_ID, Role.WORKSPACE_ADMIN));
    }

    @Test
    void changeRoleAnswersMemberNotFoundForSomeoneOutsideTheWorkspace() {
        givenMembership(Role.WORKSPACE_ADMIN, false);

        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class,
                () -> service.changeRole(user, WORKSPACE_ID, OTHER_ID, Role.WORKSPACE_ADMIN));
        assertEquals("Member not found.", e.getMessage());
    }

    @Test
    void changeRoleRefusesAMissingRole() {
        givenMembership(Role.WORKSPACE_ADMIN, false);

        assertThrows(BadRequestException.class, () -> service.changeRole(user, WORKSPACE_ID, OTHER_ID, null));
    }

    @Test
    void changeRoleLocksTheWorkspaceFirst() {
        WorkspaceMember admin = givenMembership(Role.WORKSPACE_ADMIN, false);
        givenOther(admin.getWorkspace(), Role.WORKSPACE_MEMBER);

        service.changeRole(user, WORKSPACE_ID, OTHER_ID, Role.WORKSPACE_ADMIN);

        var order = inOrder(workspaceRepository, workspaceMemberRepository);
        order.verify(workspaceRepository).lockById(WORKSPACE_ID);
        order.verify(workspaceMemberRepository).save(any(WorkspaceMember.class));
    }

    // --- remove / leave ---

    @Test
    void removeMemberLetsAnAdminRemoveSomeoneFromTheWorkspaceItsTeamsAndProjects() {
        WorkspaceMember admin = givenMembership(Role.WORKSPACE_ADMIN, false);
        WorkspaceMember other = givenOther(admin.getWorkspace(), Role.WORKSPACE_MEMBER);

        service.removeMember(user, WORKSPACE_ID, OTHER_ID);

        verify(projectMemberRepository).deleteByWorkspaceIdAndUserId(WORKSPACE_ID, OTHER_ID);
        verify(teamMemberRepository).deleteByWorkspaceIdAndUserId(WORKSPACE_ID, OTHER_ID);
        verify(taskRepository).unassignInWorkspace(WORKSPACE_ID, OTHER_ID);
        verify(workspaceMemberRepository).delete(other);
    }

    @Test
    void removeMemberLetsAMemberLeave() {
        WorkspaceMember member = givenMembership(Role.WORKSPACE_MEMBER, false);

        service.removeMember(user, WORKSPACE_ID, 7L);

        verify(projectMemberRepository).deleteByWorkspaceIdAndUserId(WORKSPACE_ID, 7L);
        verify(teamMemberRepository).deleteByWorkspaceIdAndUserId(WORKSPACE_ID, 7L);
        verify(taskRepository).unassignInWorkspace(WORKSPACE_ID, 7L);
        verify(workspaceMemberRepository).delete(member);
    }

    @Test
    void removeMemberRefusesAMemberRemovingSomeoneElse() {
        WorkspaceMember member = givenMembership(Role.WORKSPACE_MEMBER, false);
        givenOther(member.getWorkspace(), Role.WORKSPACE_MEMBER);

        assertThrows(AccessDeniedException.class, () -> service.removeMember(user, WORKSPACE_ID, OTHER_ID));
        verifyNothingRemoved();
    }

    @Test
    void removeMemberRefusesTheLastAdminLeaving() {
        givenMembership(Role.WORKSPACE_ADMIN, false);
        givenAdminCount(1);

        LastWorkspaceAdminException e = assertThrows(LastWorkspaceAdminException.class,
                () -> service.removeMember(user, WORKSPACE_ID, 7L));
        assertEquals("A workspace must have at least one admin.", e.getMessage());
        verifyNothingRemoved();
    }

    @Test
    void removeMemberLetsAnAdminLeaveWhenAnotherAdminRemains() {
        WorkspaceMember admin = givenMembership(Role.WORKSPACE_ADMIN, false);
        givenAdminCount(2);

        service.removeMember(user, WORKSPACE_ID, 7L);

        verify(workspaceMemberRepository).delete(admin);
    }

    @Test
    void removeMemberRefusesTheOnlyProjectManagerAndNamesTheProjects() {
        WorkspaceMember admin = givenMembership(Role.WORKSPACE_ADMIN, false);
        givenOther(admin.getWorkspace(), Role.WORKSPACE_MEMBER);
        when(projectMemberRepository.findProjectsWhereOnlyHolder(WORKSPACE_ID, OTHER_ID, ProjectRole.PROJECT_MANAGER))
                .thenReturn(List.of(Project.builder().name("Mobile App").build(),
                        Project.builder().name("Website").build()));

        LastProjectManagerException e = assertThrows(LastProjectManagerException.class,
                () -> service.removeMember(user, WORKSPACE_ID, OTHER_ID));
        assertEquals("A project must have at least one Project Manager. "
                + "Assign another one first in: Mobile App, Website.", e.getMessage());
        verifyNothingRemoved();
    }

    @Test
    void removeMemberLocksTheWorkspaceAndTheManagedProjectsBeforeChecking() {
        WorkspaceMember admin = givenMembership(Role.WORKSPACE_ADMIN, false);
        givenOther(admin.getWorkspace(), Role.WORKSPACE_MEMBER);

        service.removeMember(user, WORKSPACE_ID, OTHER_ID);

        var order = inOrder(workspaceRepository, projectRepository, projectMemberRepository);
        order.verify(workspaceRepository).lockById(WORKSPACE_ID);
        order.verify(projectRepository).lockAllWhereMemberHasRole(WORKSPACE_ID, OTHER_ID, ProjectRole.PROJECT_MANAGER);
        order.verify(projectMemberRepository)
                .findProjectsWhereOnlyHolder(WORKSPACE_ID, OTHER_ID, ProjectRole.PROJECT_MANAGER);
        order.verify(projectMemberRepository).deleteByWorkspaceIdAndUserId(WORKSPACE_ID, OTHER_ID);
    }

    @Test
    void removeMemberRefusesAnArchivedWorkspace() {
        givenMembership(Role.WORKSPACE_MEMBER, true);

        assertThrows(WorkspaceArchivedException.class, () -> service.removeMember(user, WORKSPACE_ID, 7L));
        verifyNothingRemoved();
    }

    @Test
    void removeMemberAnswersMemberNotFoundForSomeoneOutsideTheWorkspace() {
        givenMembership(Role.WORKSPACE_ADMIN, false);

        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class,
                () -> service.removeMember(user, WORKSPACE_ID, OTHER_ID));
        assertEquals("Member not found.", e.getMessage());
        verifyNothingRemoved();
    }

    @Test
    void removeMemberRefusesNonMembersWithWorkspaceNotFound() {
        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class,
                () -> service.removeMember(user, WORKSPACE_ID, 7L));
        assertEquals("Workspace not found.", e.getMessage());
    }

    // --- fixtures ---

    private WorkspaceMember givenOther(Workspace workspace, Role role) {
        WorkspaceMember other = WorkspaceMember.builder().id(12L).workspace(workspace).user(grace).role(role).build();
        when(workspaceMemberRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, OTHER_ID))
                .thenReturn(Optional.of(other));
        return other;
    }

    private void givenAdminCount(long count) {
        when(workspaceMemberRepository.countByWorkspaceIdAndRole(WORKSPACE_ID, Role.WORKSPACE_ADMIN))
                .thenReturn(count);
    }

    private void verifyNothingRemoved() {
        verify(projectMemberRepository, never()).deleteByWorkspaceIdAndUserId(anyLong(), anyLong());
        verify(teamMemberRepository, never()).deleteByWorkspaceIdAndUserId(anyLong(), anyLong());
        verify(taskRepository, never()).unassignInWorkspace(anyLong(), anyLong());
        verify(workspaceMemberRepository, never()).delete(any());
    }

    private WorkspaceMember givenMembership(Role role, boolean archived) {
        WorkspaceMember membership = membership(workspace(archived), role);
        when(workspaceMemberRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, 7L))
                .thenReturn(Optional.of(membership));
        return membership;
    }

    private WorkspaceMember membership(Workspace workspace, Role role) {
        return WorkspaceMember.builder().id(11L).workspace(workspace).user(user).role(role).build();
    }

    private static Workspace workspace(boolean archived) {
        return Workspace.builder().id(WORKSPACE_ID).name("Acme").slug("acme")
                .archivedAt(archived ? Instant.parse("2026-10-20T10:00:00Z") : null).build();
    }
}