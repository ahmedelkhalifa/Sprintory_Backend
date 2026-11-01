package team.sprintory_backend.task.repositories;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import team.sprintory_backend.task.models.TaskStatusHistory;

public interface TaskStatusHistoryRepository extends JpaRepository<TaskStatusHistory, Long> {
    List<TaskStatusHistory> findByTaskIdOrderByChangedAtAscIdAsc(Long taskId);
}
