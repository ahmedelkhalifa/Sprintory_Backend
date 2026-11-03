package team.sprintory_backend.notification.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import team.sprintory_backend.notification.models.Notification;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
}