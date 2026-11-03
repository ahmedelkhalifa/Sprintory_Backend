package team.sprintory_backend.notification.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import team.sprintory_backend.notification.models.EmailOutboxMessage;

public interface EmailOutboxRepository extends JpaRepository<EmailOutboxMessage, Long> {
}