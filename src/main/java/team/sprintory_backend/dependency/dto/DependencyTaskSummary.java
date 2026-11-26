package team.sprintory_backend.dependency.dto;

import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.task.models.Task;

public record DependencyTaskSummary(Long id, String key, String title, String statusName,
                                    BoardColumnCategory statusCategory) {
    public static DependencyTaskSummary from(Task task) {
        return new DependencyTaskSummary(task.getId(), task.getKey(), task.getTitle(),
                task.getStatus().getName(), task.getStatus().getCategory());
    }

    public static DependencyTaskSummary fromRow(Object[] row, int offset) {
        return new DependencyTaskSummary(((Number) row[offset]).longValue(), (String) row[offset + 1],
                (String) row[offset + 2], (String) row[offset + 3],
                BoardColumnCategory.valueOf(row[offset + 4].toString()));
    }
}
