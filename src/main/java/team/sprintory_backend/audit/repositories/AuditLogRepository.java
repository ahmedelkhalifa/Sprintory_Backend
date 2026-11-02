package team.sprintory_backend.audit.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import team.sprintory_backend.audit.models.AuditLogEntry;

/**
 * Audit log entries. Only {@code save} and reads are used: the log is append-only, and no code
 * path may update or delete an entry (a permanent workspace delete removes them by cascade).
 */
public interface AuditLogRepository extends JpaRepository<AuditLogEntry, Long>,
        JpaSpecificationExecutor<AuditLogEntry> {
}