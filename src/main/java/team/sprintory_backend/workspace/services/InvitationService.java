package team.sprintory_backend.workspace.services;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * R02 workspace invitations. An invitation is addressed to an email, so the invitee may not have
 * an account yet: they register, verify and log in first, and the frontend keeps the token in
 * the meantime. Accepting requires the logged-in email to match the invited one.
 */
@Service
@RequiredArgsConstructor
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    private final WorkspaceInvitationRepository invitationRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;
    private final WorkspaceAccess workspaceAccess;
    private final EmailService emailService;

    // Not final: @Value on a final field with @RequiredArgsConstructor fails at startup.
    @Value("${app.frontend-url}")
    private String frontendUrl;
    @Value("${app.workspace.invite-path}")
    private String invitePath;
    @Value("${app.workspace.invite-expiration-ms}")
    private long inviteTtlMs;

    /**
     * Emails an invitation to every address that is not already a member. Inviting an address
     * again replaces its earlier invitation, so only the newest link works. One transaction:
     * if any email cannot be sent, no invitation is kept (503).
     */
    @Transactional
    public InvitationsSentResponse invite(User admin, Long workspaceId, InviteMembersRequest request) {
        Workspace workspace = workspaceAccess.requireActiveAdmin(workspaceId, admin).getWorkspace();

        Set<String> emails = new LinkedHashSet<>();
        request.emails().forEach(email -> emails.add(normalizeEmail(email)));

        List<String> invited = new ArrayList<>();
        List<String> alreadyMembers = new ArrayList<>();
        for (String email : emails) {
            if (isMember(workspaceId, email)) {
                alreadyMembers.add(email);
                continue;
            }
            String token = issue(workspace, email);
            emailService.sendWorkspaceInvitation(email, displayName(admin), workspace.getName(),
                    frontendUrl + invitePath + "?token=" + token);
            invited.add(email);
        }
        return new InvitationsSentResponse(invited, alreadyMembers);
    }

    /** Lets the invitation page show the workspace and address before the invitee logs in. */
    public InvitationLookupResponse lookup(String rawToken) {
        WorkspaceInvitation invitation = findValid(rawToken);
        return new InvitationLookupResponse(invitation.getWorkspace().getName(), invitation.getEmail());
    }

    /** Adds the caller to the workspace as a member and uses the invitation up. */
    @Transactional
    public WorkspaceResponse accept(User user, String rawToken) {
        WorkspaceInvitation invitation = findValid(rawToken);
        if (!invitation.getEmail().equals(user.getEmail())) {
            throw new AccessDeniedException("This invitation was sent to a different email.");
        }
        Workspace workspace = invitation.getWorkspace();
        if (workspace.isArchived()) {
            throw new WorkspaceArchivedException("Workspace is archived.");
        }

        WorkspaceMember membership = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(workspace.getId(), user.getId())
                .orElseGet(() -> workspaceMemberRepository.save(WorkspaceMember.builder()
                        .workspace(workspace)
                        .user(user)
                        .role(Role.WORKSPACE_MEMBER)
                        .build()));
        invitationRepository.delete(invitation);
        return WorkspaceResponse.from(membership);
    }

    /** Expired rows are harmless but accumulate, so clear them out once a night. */
    @Scheduled(cron = "0 40 3 * * *")
    @Transactional
    public int deleteExpired() {
        int removed = invitationRepository.deleteExpired(Instant.now());
        if (removed > 0) {
            log.info("Removed {} expired workspace invitations", removed);
        }
        return removed;
    }

    private String issue(Workspace workspace, String email) {
        invitationRepository.deleteByWorkspaceIdAndEmail(workspace.getId(), email);

        String rawToken = OpaqueTokens.generate();
        invitationRepository.save(WorkspaceInvitation.builder()
                .workspace(workspace)
                .email(email)
                .tokenHash(OpaqueTokens.hash(rawToken))
                .expiresAt(Instant.now().plusMillis(inviteTtlMs))
                .build());
        return rawToken;
    }

    /** Unknown, used and expired links all get the same 404. */
    private WorkspaceInvitation findValid(String rawToken) {
        return Optional.ofNullable(rawToken)
                .filter(token -> !token.isBlank())
                .flatMap(token -> invitationRepository.findByTokenHash(OpaqueTokens.hash(token)))
                .filter(invitation -> !invitation.isExpired())
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found or expired."));
    }

    private boolean isMember(Long workspaceId, String email) {
        return userRepository.findByEmail(email)
                .map(user -> workspaceMemberRepository.existsByWorkspaceIdAndUserId(workspaceId, user.getId()))
                .orElse(false);
    }

    /** Google accounts can have an empty last name, hence the trim. */
    private static String displayName(User user) {
        return (user.getFirstName() + " " + user.getLastName()).trim();
    }

    /** Locale.ROOT: a Turkish default locale would lower-case "I" to a dotless "ı". */
    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}