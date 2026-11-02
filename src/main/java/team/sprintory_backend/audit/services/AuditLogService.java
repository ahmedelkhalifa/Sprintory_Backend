package team.sprintory_backend.audit.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.audit.dto.AuditActorResponse;
import team.sprintory_backend.audit.dto.AuditLogEntryResponse;
import team.sprintory_backend.audit.dto.AuditLogFilters;
import team.sprintory_backend.audit.models.AuditAction;
import team.sprintory_backend.audit.models.AuditChange;
import team.sprintory_backend.audit.models.AuditLogEntry;
import team.sprintory_backend.audit.models.AuditTarget;
import team.sprintory_backend.audit.repositories.AuditLogRepository;
import team.sprintory_backend.audit.repositories.AuditLogSpecifications;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;
import team.sprintory_backend.workspace.services.WorkspaceAccess;

/**
 * The workspace audit log (R05): reads it for workspace admins, and writes it for every other
 * service (docs/audit-log-docs.md §10).
 *
 * <p>Call it from the service method that performs the action, after the change succeeded.
 * Both methods are {@code MANDATORY}: they join the action's transaction, so a rollback removes
 * the entry too, and calling them outside a transaction fails instead of committing on its own.
 * Log only real changes, and never pass a password, token or link into a change.
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    /** Newest first; the id breaks ties so pages never overlap. Clients cannot change it. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));

    private final AuditLogRepository auditLogRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceAccess workspaceAccess;

    /**
     * One page of the workspace's log, newest first. Workspace admins only: a member gets 403,
     * anyone else 404. Archived workspaces stay readable.
     */
    @Transactional(readOnly = true)
    public PageResponse<AuditLogEntryResponse> list(User user, Long workspaceId, AuditLogFilters filters,
                                                    Pageable pageable) {
        workspaceAccess.requireAdmin(workspaceId, user);
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST);
        return PageResponse.from(auditLogRepository
                .findAll(AuditLogSpecifications.matching(workspaceId, filters), sorted)
                .map(AuditLogEntryResponse::from));
    }

    /** Everyone who appears as an actor in the log, people who left included. Admins only. */
    @Transactional(readOnly = true)
    public List<AuditActorResponse> actors(User user, Long workspaceId) {
        workspaceAccess.requireAdmin(workspaceId, user);
        return auditLogRepository.findActors(workspaceId);
    }

    /**
     * Writes one entry. {@code actor} is null when the system acted; {@code project} is null for
     * workspace, team and security actions.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Workspace workspace, User actor, AuditAction action, AuditTarget target,
                       Project project, AuditChange... changes) {
        auditLogRepository.save(entry(workspace, actor, action, target, project, changes));
    }

    /**
     * Writes the same entry into every workspace {@code subject} belongs to, for security events,
     * which belong to a person rather than a workspace. The target is the subject.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInAllWorkspacesOf(User subject, User actor, AuditAction action,
                                        AuditChange... changes) {
        AuditTarget target = AuditTarget.user(subject);
        List<AuditLogEntry> entries = workspaceMemberRepository.findAllByUserId(subject.getId()).stream()
                .map(WorkspaceMember::getWorkspace)
                .map(workspace -> entry(workspace, actor, action, target, null, changes))
                .toList();
        if (!entries.isEmpty()) {
            auditLogRepository.saveAll(entries);
        }
    }

    private static AuditLogEntry entry(Workspace workspace, User actor, AuditAction action,
                                       AuditTarget target, Project project, AuditChange... changes) {
        return AuditLogEntry.builder()
                .workspace(workspace)
                .occurredAt(Instant.now())
                .action(action)
                .actorId(actor == null ? null : actor.getId())
                .actorName(actor == null ? null : AuditTarget.displayName(actor))
                .actorEmail(actor == null ? null : actor.getEmail())
                .targetType(target.type())
                .targetId(target.id())
                .targetName(target.name())
                .projectId(project == null ? null : project.getId())
                .projectName(project == null ? null : project.getName())
                .changes(new ArrayList<>(List.of(changes)))
                .build();
    }
}