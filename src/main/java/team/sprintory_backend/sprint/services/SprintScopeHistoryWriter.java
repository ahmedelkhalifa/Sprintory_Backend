package team.sprintory_backend.sprint.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.sprint.models.Sprint;
import team.sprintory_backend.sprint.models.SprintScopeChange;
import team.sprintory_backend.sprint.repositories.SprintScopeChangeRepository;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.services.TaskStatusHistoryService;
import team.sprintory_backend.user.models.User;

/** One atomic writer for the sprint burndown and the task activity feed. */
@Service
@RequiredArgsConstructor
public class SprintScopeHistoryWriter {
    private final SprintScopeChangeRepository scopeChanges;
    private final TaskStatusHistoryService taskHistory;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Sprint sprint, Task task, SprintScopeChange.Action action, User actor) {
        scopeChanges.save(new SprintScopeChange(sprint, task.getId(), task.getKey(), action, actor.getId()));
        String previous = action == SprintScopeChange.Action.REMOVED ? sprint.getName() : null;
        String next = action == SprintScopeChange.Action.ADDED ? sprint.getName() : null;
        taskHistory.recordSprintScopeChanged(task, previous, next, actor, sprint.getId());
    }
}
