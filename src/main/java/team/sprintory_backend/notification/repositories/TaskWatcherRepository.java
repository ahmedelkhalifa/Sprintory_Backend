package team.sprintory_backend.notification.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import team.sprintory_backend.notification.models.TaskWatcher;
import team.sprintory_backend.notification.models.TaskWatcherId;

public interface TaskWatcherRepository extends JpaRepository<TaskWatcher, TaskWatcherId> {
}