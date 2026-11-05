package team.sprintory_backend.project.dto;

import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.project.models.ProjectBoardColumn;

/** Column metadata and unfiltered active task count. */
public record ProjectBoardColumnResponse(Long id, String name, int position, Integer wipLimit,
                                         BoardColumnCategory category, long taskCount, boolean wipExceeded, long subtaskCount) {
    public ProjectBoardColumnResponse(Long id, String name, int position, Integer wipLimit) {
        this(id, name, position, wipLimit, null, 0, false, 0);
    }
    /** Keep existing callers source-compatible. */
    public ProjectBoardColumnResponse(Long id, String name, int position, Integer wipLimit,
                                      BoardColumnCategory category, long taskCount, boolean wipExceeded) {
        this(id, name, position, wipLimit, category, taskCount, wipExceeded, 0);
    }

    public static ProjectBoardColumnResponse from(ProjectBoardColumn column, long count, long children) {
        return new ProjectBoardColumnResponse(column.getId(), column.getName(), column.getPosition(),
                column.getWipLimit(), column.getCategory(), count,
                column.getWipLimit() != null && count > column.getWipLimit(), children);
    }

    public static ProjectBoardColumnResponse from(ProjectBoardColumn column, long count) {
        return from(column, count, 0);
    }
    public static ProjectBoardColumnResponse from(ProjectBoardColumn column) {
        return from(column, 0);
    }
}
