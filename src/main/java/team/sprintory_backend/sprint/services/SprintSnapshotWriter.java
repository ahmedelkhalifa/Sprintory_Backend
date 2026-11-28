package team.sprintory_backend.sprint.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.sprint.models.Sprint;
import team.sprintory_backend.sprint.models.SprintTotals;
import team.sprintory_backend.sprint.repositories.SprintDailySnapshotRepository;
import team.sprintory_backend.task.repositories.TaskRepository;

import java.time.LocalDate;

/** Saves a sprint's current totals as the given day's burndown snapshot (PMP-42, docs/burndown-docs.md §5). */
@Service
@RequiredArgsConstructor
public class SprintSnapshotWriter {
    private final TaskRepository tasks;
    private final SprintDailySnapshotRepository snapshots;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Sprint sprint, LocalDate date) {
        SprintTotals totals = tasks.sprintTotals(sprint.getId(), BoardColumnCategory.DONE);
        snapshots.upsert(sprint.getId(), date,
                (int) totals.totalPoints(), (int) totals.remainingPoints(),
                (int) totals.totalTasks(), (int) totals.remainingTasks(), (int) totals.unestimatedTasks());
    }
}
