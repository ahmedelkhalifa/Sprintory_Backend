package team.sprintory_backend.dependency;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import team.sprintory_backend.notification.events.TaskUnblocked;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.user.models.User;
import java.util.*;

/** Called within a project-locked write transaction; compare only real state transitions. */
@Service
@RequiredArgsConstructor
public class UnblockedTracker {
    private final DependencyRepository dependencies;
    private final TaskRepository tasks;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;

    public Set<Long> before(Long projectId) {
        entityManager.flush();
        return new HashSet<>(dependencies.blockedIds(projectId));
    }

    public void after(Long projectId, Set<Long> before, User actor) {
        if (before.isEmpty()) return;
        entityManager.flush();
        Set<Long> stillBlocked = new HashSet<>(dependencies.blockedIds(projectId));
        for (Long id : before) {
            if (!stillBlocked.contains(id)) tasks.findByIdAndProjectId(id,projectId)
                .ifPresent(task -> events.publishEvent(new TaskUnblocked(task,actor)));
        }
    }
}
