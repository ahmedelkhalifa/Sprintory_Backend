package team.sprintory_backend.dependency;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import team.sprintory_backend.dependency.DependencyDtos.TaskSummary;
import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.project.models.ProjectBoardColumn;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.exceptionHandler.ErrorCode;
import java.util.*;

/** One batched query per task page, never a stored blocked flag. */
@Service
@RequiredArgsConstructor
public class BlockedStateService {
    private final DependencyRepository dependencies;

    public Map<Long,List<TaskSummary>> openBlockers(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long,List<TaskSummary>> result = new HashMap<>();
        for (Object[] row : dependencies.openBlockers(ids)) {
            result.computeIfAbsent(((Number) row[0]).longValue(), k -> new ArrayList<>()).add(TaskSummary.fromRow(row,1));
        }
        result.replaceAll((id, list) -> List.copyOf(list));
        return result;
    }

    public void requireMoveConfirmation(Task task, ProjectBoardColumn target, boolean confirmed) {
        if (confirmed || task.getParent()!=null || Objects.equals(task.getStatus().getId(), target.getId())
                || target.getCategory()==BoardColumnCategory.TODO) return;
        // Reordering inside one category is not a forward transition. Reopening DONE is not forward.
        BoardColumnCategory from = task.getStatus().getCategory();
        if (from == BoardColumnCategory.DONE || from == target.getCategory()) return;
        List<TaskSummary> blockers = openBlockers(List.of(task.getId())).getOrDefault(task.getId(), List.of());
        if (!blockers.isEmpty()) throw new DependencyConflict(ErrorCode.BLOCKED_TASK_CONFIRMATION_REQUIRED,
            "This task is blocked. Move it anyway?", null, blockers);
    }
}
