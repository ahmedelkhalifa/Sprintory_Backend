package team.sprintory_backend.audit.repositories;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;
import team.sprintory_backend.audit.dto.AuditLogFilters;
import team.sprintory_backend.audit.models.AuditLogEntry;

/**
 * The query behind a workspace's audit log. Always limited to one workspace; every given filter
 * narrows it further (AND).
 */
public final class AuditLogSpecifications {

    private AuditLogSpecifications() {
    }

    public static Specification<AuditLogEntry> matching(Long workspaceId, AuditLogFilters filters) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("workspace").get("id"), workspaceId));
            if (filters.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), filters.from()));
            }
            if (filters.to() != null) {
                predicates.add(cb.lessThan(root.get("occurredAt"), filters.to()));
            }
            if (filters.actorId() != null) {
                predicates.add(cb.equal(root.get("actorId"), filters.actorId()));
            }
            if (filters.projectId() != null) {
                predicates.add(cb.equal(root.get("projectId"), filters.projectId()));
            }
            if (filters.actions() != null) {
                // An empty set (e.g. areas and actions that do not overlap) matches nothing.
                predicates.add(filters.actions().isEmpty()
                        ? cb.disjunction()
                        : root.get("action").in(filters.actions()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}