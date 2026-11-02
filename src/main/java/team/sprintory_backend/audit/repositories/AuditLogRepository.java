package team.sprintory_backend.audit.repositories;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import team.sprintory_backend.audit.dto.AuditActorResponse;
import team.sprintory_backend.audit.models.AuditLogEntry;

/**
 * Audit log entries. Only {@code save} and reads are used: the log is append-only, and no code
 * path may update or delete an entry (a permanent workspace delete removes them by cascade).
 */
public interface AuditLogRepository extends JpaRepository<AuditLogEntry, Long>,
        JpaSpecificationExecutor<AuditLogEntry> {

    /**
     * Everyone who acted in the workspace, once each, with the name and email of their latest
     * entry (the highest id), sorted by name. The system actor (null) is left out.
     */
    @Query("select new team.sprintory_backend.audit.dto.AuditActorResponse(e.actorId, e.actorName, e.actorEmail) "
            + "from AuditLogEntry e where e.workspace.id = :workspaceId and e.actorId is not null "
            + "and e.id = (select max(latest.id) from AuditLogEntry latest "
            + "where latest.workspace.id = :workspaceId and latest.actorId = e.actorId) "
            + "order by lower(e.actorName), e.actorId")
    List<AuditActorResponse> findActors(@Param("workspaceId") Long workspaceId);
}