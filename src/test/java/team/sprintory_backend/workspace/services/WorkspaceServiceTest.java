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
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.dto.CreateWorkspaceRequest;
import team.sprintory_backend.workspace.dto.UpdateWorkspaceRequest;
import team.sprintory_backend.workspace.dto.WorkspaceMemberResponse;
import team.sprintory_backend.workspace.dto.WorkspaceResponse;
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

    private WorkspaceRepository workspaceRepository;
    private WorkspaceMemberRepository workspaceMemberRepository;
    private SlugGenerator slugGenerator;
    private WorkspaceService service;
    private User user;

    @BeforeEach
    void setUp() {
        workspaceRepository = mock(WorkspaceRepository.class);
        workspaceMemberRepository = mock(WorkspaceMemberRepository.class);
        slugGenerator = mock(SlugGenerator.class);
        service = new WorkspaceService(workspaceRepository, workspaceMemberRepository,
                new WorkspaceAccess(workspaceMemberRepository), slugGenerator);
        user = User.builder().id(7L).firstName("Ada").lastName("Lovelace").email("ada@example.com").build();
        when(workspaceMemberRepository.findByWorkspaceIdAndUserId(anyLong(), anyLong())).thenReturn(Optional.empty());
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

    // --- fixtures ---

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