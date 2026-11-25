package team.sprintory_backend.dependency.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import team.sprintory_backend.dependency.dto.DependencyTaskSummary;
import team.sprintory_backend.dependency.exceptions.DependencyConflict;
import team.sprintory_backend.dependency.repositories.DependencyRepository;
import team.sprintory_backend.exceptionHandler.ErrorCode;
import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.project.models.ProjectBoardColumn;
import team.sprintory_backend.task.models.Task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One batched query per task page, never a stored blocked flag. */
@Service
@RequiredArgsConstructor
public class BlockedStateService {
    private final DependencyRepository dependencies;

    public Map<Long,List<DependencyTaskSummary>> openBlockers(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long,List<DependencyTaskSummary>> result = new HashMap<>();
        for (Object[] row : dependencies.openBlockers(ids)) {
            result.computeIfAbsent(((Number) row[0]).longValue(), k -> new ArrayList<>()).add(DependencyTaskSummary.fromRow(row,1));
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
        List<DependencyTaskSummary> blockers = openBlockers(List.of(task.getId())).getOrDefault(task.getId(), List.of());
        if (!blockers.isEmpty()) throw new DependencyConflict(ErrorCode.BLOCKED_TASK_CONFIRMATION_REQUIRED,
            "This task is blocked. Move it anyway?", null, blockers);
    }
}
