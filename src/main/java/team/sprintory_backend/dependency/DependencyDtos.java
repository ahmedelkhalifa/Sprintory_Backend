package team.sprintory_backend.dependency;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.task.models.Task;
import java.util.List;

public final class DependencyDtos {
    private DependencyDtos() {}
    public record CreateRequest(@NotNull @Positive Long blockerId, @NotNull @Positive Long blockedId) {}
    public record TaskSummary(Long id, String key, String title, String statusName, BoardColumnCategory statusCategory) {
        static TaskSummary from(Task task) {
            return new TaskSummary(task.getId(), task.getKey(), task.getTitle(), task.getStatus().getName(), task.getStatus().getCategory());
        }
        static TaskSummary fromRow(Object[] row, int offset) {
            return new TaskSummary(((Number) row[offset]).longValue(), (String) row[offset+1],
                (String) row[offset+2], (String) row[offset+3], BoardColumnCategory.valueOf(row[offset+4].toString()));
        }
    }
    public record Edge(Long id, TaskSummary blocker, TaskSummary blocked) {}
    public record Link(Long id, TaskSummary task) {}
    public record Response(boolean blocked, List<Link> blockedBy, List<Link> blocks, List<TaskSummary> openBlockers, boolean canManage) {}
}
