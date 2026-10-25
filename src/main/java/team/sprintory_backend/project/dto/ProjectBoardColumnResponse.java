package team.sprintory_backend.project.dto;

import team.sprintory_backend.project.models.ProjectBoardColumn;

/** The stable column ID is available for future add/rename/reorder/task APIs. */
public record ProjectBoardColumnResponse(Long id, String name, int position, Integer wipLimit) {
    public static ProjectBoardColumnResponse from(ProjectBoardColumn column) {
        return new ProjectBoardColumnResponse(column.getId(), column.getName(), column.getPosition(),
                column.getWipLimit());
    }
}
