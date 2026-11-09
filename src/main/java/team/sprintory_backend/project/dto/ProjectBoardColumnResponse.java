package team.sprintory_backend.project.dto;

import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.project.models.ProjectBoardColumn;

/**
 * Column metadata and counts. {@code taskCount} / {@code subtaskCount} are project-wide and
 * unfiltered (WIP and the column-delete target rule use them). For Scrum,
 * {@code sprintTaskCount} / {@code sprintSubtaskCount} count only the active sprint's items, the
 * cards the Scrum board shows (PMP-40): 0 when no sprint is active, null for Kanban.
 */
public record ProjectBoardColumnResponse(Long id, String name, int position, Integer wipLimit,
                                         BoardColumnCategory category, long taskCount, boolean wipExceeded,
                                         long subtaskCount, Long sprintTaskCount, Long sprintSubtaskCount) {
    public ProjectBoardColumnResponse(Long id, String name, int position, Integer wipLimit) {
        this(id, name, position, wipLimit, null, 0, false, 0, null, null);
    }
    /** Keep existing callers source-compatible. */
    public ProjectBoardColumnResponse(Long id, String name, int position, Integer wipLimit,
                                      BoardColumnCategory category, long taskCount, boolean wipExceeded) {
        this(id, name, position, wipLimit, category, taskCount, wipExceeded, 0, null, null);
    }

    public static ProjectBoardColumnResponse from(ProjectBoardColumn column, long count, long children,
                                                  Long sprintCount, Long sprintChildren) {
        return new ProjectBoardColumnResponse(column.getId(), column.getName(), column.getPosition(),
                column.getWipLimit(), column.getCategory(), count,
                column.getWipLimit() != null && count > column.getWipLimit(), children, sprintCount, sprintChildren);
    }

    public static ProjectBoardColumnResponse from(ProjectBoardColumn column, long count, long children) {
        return from(column, count, children, null, null);
    }

    public static ProjectBoardColumnResponse from(ProjectBoardColumn column, long count) {
        return from(column, count, 0);
    }
    public static ProjectBoardColumnResponse from(ProjectBoardColumn column) {
        return from(column, 0);
    }
}