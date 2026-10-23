package team.sprintory_backend.workspace.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.notification.email.EmailDeliveryException;
import team.sprintory_backend.notification.email.EmailService;
import team.sprintory_backend.security.auth.services.OpaqueTokens;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.dto.InvitationLookupResponse;
import team.sprintory_backend.workspace.dto.InvitationsSentResponse;
import team.sprintory_backend.workspace.dto.InviteMembersRequest;
import team.sprintory_backend.workspace.dto.WorkspaceResponse;
import team.sprintory_backend.workspace.exceptions.WorkspaceArchivedException;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceInvitation;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceInvitationRepository;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Pure service tests: repositories and email are mocked, so no database, Spring context or SMTP.
 * WorkspaceAccess is real, so the admin and archived checks are tested here too.
 */
class InvitationServiceTest {

    private static final Long WORKSPACE_ID = 3L;
    private static final long SEVEN_DAYS_MS = 604_800_000L;

    private WorkspaceInvitationRepository invitationRepository;
    private WorkspaceMemberRepository workspaceMemberRepository;
    private UserRepository userRepository;
    private EmailService emailService;
    private InvitationService service;
    private User admin;

    @BeforeEach
    void setUp() {
        invitationRepository = mock(WorkspaceInvitationRepository.class);
        workspaceMemberRepository = mock(WorkspaceMemberRepository.class);
        userRepository = mock(UserRepository.class);
        emailService = mock(EmailService.class);
        service = new InvitationService(invitationRepository, workspaceMemberRepository, userRepository,
                new WorkspaceAccess(workspaceMemberRepository), emailService);
        ReflectionTestUtils.setField(service, "frontendUrl", "http://front");
        ReflectionTestUtils.setField(service, "invitePath", "/accept-invite");
        ReflectionTestUtils.setField(service, "inviteTtlMs", SEVEN_DAYS_MS);

        admin = User.builder().id(7L).firstName("Ada").lastName("Lovelace").email("ada@example.com").build();
        when(workspaceMemberRepository.findByWorkspaceIdAndUserId(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(invitationRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());
    }

    // --- invite ---

    @Test
    void inviteRefusesPlainMembers() {
        givenMembership(admin, Role.WORKSPACE_MEMBER, false);

        assertThrows(AccessDeniedException.class, () -> invite("grace@example.com"));
        verifyNoInteractions(emailService);
    }

    @Test
    void inviteRefusesAnArchivedWorkspace() {
        givenMembership(admin, Role.WORKSPACE_ADMIN, true);

        assertThrows(WorkspaceArchivedException.class, () -> invite("grace@example.com"));
        verifyNoInteractions(emailService);
    }

    @Test
    void inviteEmailsALinkWhoseTokenIsStoredOnlyAsAHash() {
        givenMembership(admin, Role.WORKSPACE_ADMIN, false);
        Instant before = Instant.now();

        InvitationsSentResponse response = invite("grace@example.com");

        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendWorkspaceInvitation(eq("grace@example.com"), eq("Ada Lovelace"), eq("Acme"),
                link.capture());
        assertTrue(link.getValue().startsWith("http://front/accept-invite?token="));
        String rawToken = link.getValue().substring(link.getValue().indexOf("=") + 1);

        ArgumentCaptor<WorkspaceInvitation> saved = ArgumentCaptor.forClass(WorkspaceInvitation.class);
        verify(invitationRepository).save(saved.capture());
        assertEquals("grace@example.com", saved.getValue().getEmail());
        assertEquals(OpaqueTokens.hash(rawToken), saved.getValue().getTokenHash());
        assertNotEquals(rawToken, saved.getValue().getTokenHash());
        Duration ttl = Duration.between(before, saved.getValue().getExpiresAt());
        assertTrue(ttl.compareTo(Duration.ofDays(7).minusMinutes(1)) > 0
                && ttl.compareTo(Duration.ofDays(7).plusMinutes(1)) < 0);

        assertEquals(List.of("grace@example.com"), response.invited());
        assertEquals(List.of(), response.alreadyMembers());
    }

    @Test
    void inviteReplacesTheEarlierInvitationBeforeSavingTheNewOne() {
        givenMembership(admin, Role.WORKSPACE_ADMIN, false);

        invite("grace@example.com");

        InOrder order = inOrder(invitationRepository);
        order.verify(invitationRepository).deleteByWorkspaceIdAndEmail(WORKSPACE_ID, "grace@example.com");
        order.verify(invitationRepository).save(any(WorkspaceInvitation.class));
    }

    @Test
    void inviteNormalizesAddressesAndSendsDuplicatesOnce() {
        givenMembership(admin, Role.WORKSPACE_ADMIN, false);

        InvitationsSentResponse response = invite("  Grace@Example.COM ", "grace@example.com");

        verify(emailService, times(1)).sendWorkspaceInvitation(eq("grace@example.com"), anyString(), anyString(),
                anyString());
        assertEquals(List.of("grace@example.com"), response.invited());
    }

    @Test
    void inviteSkipsPeopleWhoAreAlreadyMembers() {
        givenMembership(admin, Role.WORKSPACE_ADMIN, false);
        User linus = user(9L, "linus@example.com");
        when(userRepository.findByEmail("linus@example.com")).thenReturn(Optional.of(linus));
        when(workspaceMemberRepository.existsByWorkspaceIdAndUserId(WORKSPACE_ID, 9L)).thenReturn(true);

        InvitationsSentResponse response = invite("linus@example.com", "grace@example.com");

        verify(emailService, never()).sendWorkspaceInvitation(eq("linus@example.com"), anyString(), anyString(),
                anyString());
        assertEquals(List.of("grace@example.com"), response.invited());
        assertEquals(List.of("linus@example.com"), response.alreadyMembers());
    }

    @Test
    void inviteInvitesAnAccountThatIsNotAMemberYet() {
        givenMembership(admin, Role.WORKSPACE_ADMIN, false);
        when(userRepository.findByEmail("linus@example.com")).thenReturn(Optional.of(user(9L, "linus@example.com")));
        when(workspaceMemberRepository.existsByWorkspaceIdAndUserId(WORKSPACE_ID, 9L)).thenReturn(false);

        InvitationsSentResponse response = invite("linus@example.com");

        assertEquals(List.of("linus@example.com"), response.invited());
    }

    @Test
    void inviteLetsAnEmailFailureStopTheRequest() {
        givenMembership(admin, Role.WORKSPACE_ADMIN, false);
        doThrow(new EmailDeliveryException("SMTP down")).when(emailService)
                .sendWorkspaceInvitation(anyString(), anyString(), anyString(), anyString());

        // Propagating is what makes the transaction roll back and the advice answer 503.
        assertThrows(EmailDeliveryException.class, () -> invite("grace@example.com"));
    }

    // --- lookup ---

    @Test
    void lookupRefusesUnknownAndBlankTokensWithNotFound() {
        assertThrows(ResourceNotFoundException.class, () -> service.lookup("unknown"));
        assertThrows(ResourceNotFoundException.class, () -> service.lookup("  "));
        assertThrows(ResourceNotFoundException.class, () -> service.lookup(null));
    }

    @Test
    void lookupRefusesAnExpiredInvitationWithNotFound() {
        givenInvitation("tok", "grace@example.com", false, Instant.now().minusSeconds(60));

        assertThrows(ResourceNotFoundException.class, () -> service.lookup("tok"));
    }

    @Test
    void lookupReturnsTheWorkspaceNameAndInvitedEmail() {
        givenInvitation("tok", "grace@example.com", false, Instant.now().plusSeconds(3600));

        assertEquals(new InvitationLookupResponse("Acme", "grace@example.com"), service.lookup("tok"));
    }

    // --- accept ---

    @Test
    void acceptRefusesAnotherEmail() {
        givenInvitation("tok", "grace@example.com", false, Instant.now().plusSeconds(3600));

        assertThrows(AccessDeniedException.class, () -> service.accept(admin, "tok"));
        verify(workspaceMemberRepository, never()).save(any());
        verify(invitationRepository, never()).delete(any());
    }

    @Test
    void acceptRefusesAnArchivedWorkspace() {
        User grace = user(12L, "grace@example.com");
        givenInvitation("tok", "grace@example.com", true, Instant.now().plusSeconds(3600));

        assertThrows(WorkspaceArchivedException.class, () -> service.accept(grace, "tok"));
        verify(workspaceMemberRepository, never()).save(any());
    }

    @Test
    void acceptRefusesAnExpiredInvitation() {
        User grace = user(12L, "grace@example.com");
        givenInvitation("tok", "grace@example.com", false, Instant.now().minusSeconds(60));

        assertThrows(ResourceNotFoundException.class, () -> service.accept(grace, "tok"));
    }

    @Test
    void acceptAddsTheInviteeAsMemberAndUsesTheInvitationUp() {
        User grace = user(12L, "grace@example.com");
        WorkspaceInvitation invitation = givenInvitation("tok", "grace@example.com", false,
                Instant.now().plusSeconds(3600));
        when(workspaceMemberRepository.save(any(WorkspaceMember.class))).thenAnswer(call -> call.getArgument(0));

        WorkspaceResponse response = service.accept(grace, "tok");

        ArgumentCaptor<WorkspaceMember> member = ArgumentCaptor.forClass(WorkspaceMember.class);
        verify(workspaceMemberRepository).save(member.capture());
        assertSame(grace, member.getValue().getUser());
        assertEquals(Role.WORKSPACE_MEMBER, member.getValue().getRole());
        verify(invitationRepository).delete(invitation);
        assertEquals(Role.WORKSPACE_MEMBER, response.role());
        assertEquals(WORKSPACE_ID, response.id());
    }

    @Test
    void acceptByAnExistingMemberOnlyUsesTheInvitationUp() {
        User grace = user(12L, "grace@example.com");
        WorkspaceInvitation invitation = givenInvitation("tok", "grace@example.com", false,
                Instant.now().plusSeconds(3600));
        givenMembership(grace, Role.WORKSPACE_ADMIN, false);

        WorkspaceResponse response = service.accept(grace, "tok");

        verify(workspaceMemberRepository, never()).save(any());
        verify(invitationRepository).delete(invitation);
        assertEquals(Role.WORKSPACE_ADMIN, response.role());
    }

    // --- cleanup ---

    @Test
    void deleteExpiredReturnsTheNumberRemoved() {
        when(invitationRepository.deleteExpired(any(Instant.class))).thenReturn(4);

        assertEquals(4, service.deleteExpired());
    }

    // --- fixtures ---

    private InvitationsSentResponse invite(String... emails) {
        return service.invite(admin, WORKSPACE_ID, new InviteMembersRequest(List.of(emails)));
    }

    private void givenMembership(User member, Role role, boolean archived) {
        when(workspaceMemberRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, member.getId()))
                .thenReturn(Optional.of(WorkspaceMember.builder()
                        .id(11L).workspace(workspace(archived)).user(member).role(role).build()));
    }

    private WorkspaceInvitation givenInvitation(String rawToken, String email, boolean archived, Instant expiresAt) {
        WorkspaceInvitation invitation = WorkspaceInvitation.builder()
                .id(30L).workspace(workspace(archived)).email(email)
                .tokenHash(OpaqueTokens.hash(rawToken)).expiresAt(expiresAt).build();
        when(invitationRepository.findByTokenHash(OpaqueTokens.hash(rawToken))).thenReturn(Optional.of(invitation));
        return invitation;
    }

    private static Workspace workspace(boolean archived) {
        return Workspace.builder().id(WORKSPACE_ID).name("Acme").slug("acme")
                .archivedAt(archived ? Instant.parse("2026-10-20T10:00:00Z") : null).build();
    }

    /** Invitees; only the id and email matter to these tests. */
    private static User user(Long id, String email) {
        return User.builder().id(id).firstName("Invitee").lastName("").email(email).build();
    }
}