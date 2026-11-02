package team.sprintory_backend.audit.controllers;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import team.sprintory_backend.audit.dto.AuditActorResponse;
import team.sprintory_backend.audit.dto.AuditLogEntryResponse;
import team.sprintory_backend.audit.dto.AuditLogFilters;
import team.sprintory_backend.audit.services.AuditLogService;
import team.sprintory_backend.common.Pageables;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.security.userdetails.AppUserDetails;

/**
 * R05: a workspace's audit log, for its admins. Read-only on purpose: there is no endpoint to
 * create, edit or delete an entry. Contract: docs/audit-log-docs.md.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/audit-log")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    /**
     * {@code ?from=&to=} (ISO-8601 instants, to exclusive), {@code ?actorId=&projectId=},
     * {@code ?actions=&areas=} (comma lists); {@code ?page=&size=} pages (max 100), newest first.
     */
    @GetMapping
    public PageResponse<AuditLogEntryResponse> list(@AuthenticationPrincipal AppUserDetails principal,
                                                    @PathVariable Long workspaceId,
                                                    @RequestParam(required = false) String from,
                                                    @RequestParam(required = false) String to,
                                                    @RequestParam(required = false) Long actorId,
                                                    @RequestParam(required = false) Long projectId,
                                                    @RequestParam(required = false) String actions,
                                                    @RequestParam(required = false) String areas,
                                                    Pageable pageable) {
        AuditLogFilters filters = AuditLogFilters.fromQuery(from, to, actorId, projectId, actions, areas);
        return auditLogService.list(principal.getUser(), workspaceId, filters, Pageables.withoutSort(pageable));
    }

    /** Everyone who appears as an actor, for the actor filter; people who left are included. */
    @GetMapping("/actors")
    public List<AuditActorResponse> actors(@AuthenticationPrincipal AppUserDetails principal,
                                           @PathVariable Long workspaceId) {
        return auditLogService.actors(principal.getUser(), workspaceId);
    }
}