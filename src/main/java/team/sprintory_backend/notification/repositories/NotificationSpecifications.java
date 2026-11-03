package team.sprintory_backend.notification.repositories;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;
import team.sprintory_backend.notification.models.Notification;

/** The bell list: always one person's notifications; the optional filters narrow it (AND). */
public final class NotificationSpecifications {

    private NotificationSpecifications() {
    }

    public static Specification<Notification> forRecipient(Long recipientId, boolean unreadOnly, Long workspaceId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("recipient").get("id"), recipientId));
            if (unreadOnly) {
                predicates.add(cb.isNull(root.get("readAt")));
            }
            if (workspaceId != null) {
                predicates.add(cb.equal(root.get("workspace").get("id"), workspaceId));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}