package team.sprintory_backend.team.services;

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
import team.sprintory_backend.team.dto.AddTeamMembersRequest;
import team.sprintory_backend.team.dto.CreateTeamRequest;
import team.sprintory_backend.team.dto.TeamResponse;
import team.sprintory_backend.team.dto.UpdateTeamRequest;
import team.sprintory_backend.team.models.Team;
import team.sprintory_backend.team.models.TeamMember;
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
class TeamServiceTest {

    private static final Long WORKSPACE_ID = 3L;
    private static final Long TEAM_ID = 4L;

    private TeamRepository teamRepository;
    private TeamMemberRepository teamMemberRepository;
    private WorkspaceMemberRepository workspaceMemberRepository;
    private TeamService service;

    private Workspace workspace;
    private Team team;
    private final User admin = user(7L, "ada@example.com");
    private final User member = user(8L, "grace@example.com");

    @BeforeEach
    void setUp() {
        teamRepository = mock(TeamRepository.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        workspaceMemberRepository = mock(WorkspaceMemberRepository.class);
        service = new TeamService(teamRepository, teamMemberRepository, workspaceMemberRepository,
                new WorkspaceAccess(workspaceMemberRepository));

        workspace = Workspace.builder().id(WORKSPACE_ID).name("Acme").slug("acme").build();
        team = Team.builder().id(TEAM_ID).workspace(workspace).name("Backend").build();
        when(workspaceMemberRepository.findByWorkspaceIdAndUserId(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(teamRepository.findWithWorkspaceById(anyLong())).thenReturn(Optional.empty());
        when(teamRepository.findWithWorkspaceById(TEAM_ID)).thenReturn(Optional.of(team));
        when(teamMemberRepository.existsByTeamIdAndUserId(anyLong(), anyLong())).thenReturn(false);
    }

    // --- create ---

    @Test
    void createRefusesNonMembersWithWorkspaceNotFound() {
        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class,
                () -> service.create(member, WORKSPACE_ID, new CreateTeamRequest("Backend")));
        assertEquals("Workspace not found.", e.getMessage());
    }

    @Test
    void createRefusesPlainMembers() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);

        assertThrows(AccessDeniedException.class,
                () -> service.create(member, WORKSPACE_ID, new CreateTeamRequest("Backend")));
        verify(teamRepository, never()).saveAndFlush(any());
    }

    @Test
    void createRefusesAnArchivedWorkspace() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        archiveWorkspace();

        assertThrows(WorkspaceArchivedException.class,
                () -> service.create(admin, WORKSPACE_ID, new CreateTeamRequest("Backend")));
        verify(teamRepository, never()).saveAndFlush(any());
    }

    @Test
    void createTrimsTheNameAndFlushesSoADuplicateFailsImmediately() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        when(teamRepository.saveAndFlush(any(Team.class))).thenAnswer(call -> {
            Team saved = call.getArgument(0);
            saved.setId(TEAM_ID);
            return saved;
        });

        TeamResponse response = service.create(admin, WORKSPACE_ID, new CreateTeamRequest("  Backend "));

        ArgumentCaptor<Team> saved = ArgumentCaptor.forClass(Team.class);
        verify(teamRepository).saveAndFlush(saved.capture());
        assertSame(workspace, saved.getValue().getWorkspace());
        assertEquals(new TeamResponse(TEAM_ID, WORKSPACE_ID, "Backend"), response);
    }

    // --- list ---

    @Test
    void listGivesAdminsEveryTeam() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        when(teamRepository.searchByWorkspaceId(anyLong(), anyString(), any()))
                .thenReturn(new PageImpl<>(List.of(team), PageRequest.of(0, 20), 1));

        PageResponse<TeamResponse> page = service.list(admin, WORKSPACE_ID, "Back",
                PageRequest.of(0, 20, Sort.by("name")));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(teamRepository).searchByWorkspaceId(eq(WORKSPACE_ID), eq("%back%"), pageable.capture());
        assertTrue(pageable.getValue().getSort().isUnsorted());
        verify(teamRepository, never()).searchByWorkspaceIdAndMemberId(anyLong(), anyLong(), anyString(), any());
        assertEquals(List.of(new TeamResponse(TEAM_ID, WORKSPACE_ID, "Backend")), page.content());
    }

    @Test
    void listGivesPlainMembersOnlyTheirOwnTeams() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);
        when(teamRepository.searchByWorkspaceIdAndMemberId(anyLong(), anyLong(), anyString(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.list(member, WORKSPACE_ID, null, PageRequest.of(0, 20));

        verify(teamRepository).searchByWorkspaceIdAndMemberId(eq(WORKSPACE_ID), eq(8L), eq("%"), any());
        verify(teamRepository, never()).searchByWorkspaceId(anyLong(), anyString(), any());
    }

    @Test
    void listRefusesNonMembers() {
        assertThrows(ResourceNotFoundException.class,
                () -> service.list(member, WORKSPACE_ID, null, PageRequest.of(0, 20)));
    }

    // --- visibility (get, listMembers) ---

    @Test
    void getRefusesAMissingTeam() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);

        assertTeamNotFound(() -> service.get(admin, 99L));
    }

    @Test
    void getAnswersTeamNotFoundToSomeoneOutsideTheWorkspace() {
        assertTeamNotFound(() -> service.get(member, TEAM_ID));
    }

    @Test
    void getAnswersTeamNotFoundToAMemberWhoIsNotInTheTeam() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);

        assertTeamNotFound(() -> service.get(member, TEAM_ID));
    }

    @Test
    void getAllowsAMemberOfTheTeam() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);
        givenInTeam(member);

        assertEquals("Backend", service.get(member, TEAM_ID).name());
    }

    @Test
    void getAllowsAnAdminWhoIsNotInTheTeam() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);

        assertEquals("Backend", service.get(admin, TEAM_ID).name());
    }

    @Test
    void getWorksOnAnArchivedWorkspace() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        archiveWorkspace();

        assertEquals("Backend", service.get(admin, TEAM_ID).name());
    }

    @Test
    void listMembersSearchesTheTeam() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);
        givenInTeam(member);
        TeamMember row = TeamMember.builder().team(team).user(member).build();
        when(teamMemberRepository.searchByTeamId(anyLong(), anyString(), any()))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

        var page = service.listMembers(member, TEAM_ID, "Grace", PageRequest.of(0, 20));

        verify(teamMemberRepository).searchByTeamId(eq(TEAM_ID), eq("%grace%"), any());
        assertEquals(8L, page.content().get(0).userId());
    }

    @Test
    void listMembersRefusesWhoeverCannotSeeTheTeam() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);

        assertTeamNotFound(() -> service.listMembers(member, TEAM_ID, null, PageRequest.of(0, 20)));
        verify(teamMemberRepository, never()).searchByTeamId(anyLong(), anyString(), any());
    }

    // --- rename / delete ---

    @Test
    void renameRefusesATeamMemberWhoIsNotAnAdminWithForbidden() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);
        givenInTeam(member);

        assertThrows(AccessDeniedException.class,
                () -> service.rename(member, TEAM_ID, new UpdateTeamRequest("Platform")));
        verify(teamRepository, never()).saveAndFlush(any());
    }

    @Test
    void renameRefusesAnArchivedWorkspace() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        archiveWorkspace();

        assertThrows(WorkspaceArchivedException.class,
                () -> service.rename(admin, TEAM_ID, new UpdateTeamRequest("Platform")));
        verify(teamRepository, never()).saveAndFlush(any());
    }

    @Test
    void renameTrimsTheNameAndFlushes() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);

        TeamResponse response = service.rename(admin, TEAM_ID, new UpdateTeamRequest(" Platform "));

        verify(teamRepository).saveAndFlush(team);
        assertEquals("Platform", response.name());
    }

    @Test
    void deleteRefusesAMemberWhoCannotSeeTheTeamWithNotFound() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);

        assertTeamNotFound(() -> service.delete(member, TEAM_ID));
        verify(teamRepository, never()).delete(any());
    }

    @Test
    void deleteRemovesTheTeam() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);

        service.delete(admin, TEAM_ID);

        verify(teamRepository).delete(team);
    }

    // --- add / remove members ---

    @Test
    void addMembersIsAllOrNothing() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        // Asked for 8 and 99; only 8 is in the workspace.
        when(workspaceMemberRepository.findAllByWorkspaceIdAndUserIdIn(eq(WORKSPACE_ID), any()))
                .thenReturn(List.of(membership(member, Role.WORKSPACE_MEMBER)));

        assertThrows(BadRequestException.class,
                () -> service.addMembers(admin, TEAM_ID, new AddTeamMembersRequest(List.of(8L, 99L))));
        verify(teamMemberRepository, never()).save(any());
    }

    @Test
    void addMembersSkipsPeopleAlreadyInTheTeamAndCountsDuplicatesOnce() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        User linus = user(12L, "linus@example.com");
        when(workspaceMemberRepository.findAllByWorkspaceIdAndUserIdIn(WORKSPACE_ID, Set.of(8L, 12L)))
                .thenReturn(List.of(membership(member, Role.WORKSPACE_MEMBER), membership(linus, Role.WORKSPACE_MEMBER)));
        givenInTeam(member);

        service.addMembers(admin, TEAM_ID, new AddTeamMembersRequest(List.of(8L, 8L, 12L)));

        ArgumentCaptor<TeamMember> saved = ArgumentCaptor.forClass(TeamMember.class);
        verify(teamMemberRepository, times(1)).save(saved.capture());
        assertSame(linus, saved.getValue().getUser());
        assertSame(team, saved.getValue().getTeam());
    }

    @Test
    void addMembersRefusesAnArchivedWorkspace() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        archiveWorkspace();

        assertThrows(WorkspaceArchivedException.class,
                () -> service.addMembers(admin, TEAM_ID, new AddTeamMembersRequest(List.of(8L))));
    }

    @Test
    void removeMemberDeletesTheRowEvenWhenThereIsNone() {
        givenWorkspaceRole(admin, Role.WORKSPACE_ADMIN);
        when(teamMemberRepository.deleteByTeamIdAndUserId(TEAM_ID, 12L)).thenReturn(0);

        assertDoesNotThrow(() -> service.removeMember(admin, TEAM_ID, 12L));
        verify(teamMemberRepository).deleteByTeamIdAndUserId(TEAM_ID, 12L);
    }

    @Test
    void removeMemberRefusesATeamMemberWhoIsNotAnAdmin() {
        givenWorkspaceRole(member, Role.WORKSPACE_MEMBER);
        givenInTeam(member);

        assertThrows(AccessDeniedException.class, () -> service.removeMember(member, TEAM_ID, 12L));
        verify(teamMemberRepository, never()).deleteByTeamIdAndUserId(anyLong(), anyLong());
    }

    // --- fixtures ---

    private void givenWorkspaceRole(User user, Role role) {
        when(workspaceMemberRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, user.getId()))
                .thenReturn(Optional.of(membership(user, role)));
    }

    private void givenInTeam(User user) {
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, user.getId())).thenReturn(true);
    }

    /** The team and every membership share one workspace object, so archiving it applies to all. */
    private void archiveWorkspace() {
        workspace.setArchivedAt(Instant.parse("2026-10-20T10:00:00Z"));
    }

    private WorkspaceMember membership(User user, Role role) {
        return WorkspaceMember.builder().workspace(workspace).user(user).role(role).build();
    }

    private static void assertTeamNotFound(Executable call) {
        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class, call);
        assertEquals("Team not found.", e.getMessage());
    }

    private static User user(Long id, String email) {
        return User.builder().id(id).firstName("User").lastName(String.valueOf(id)).email(email).build();
    }
}