package team.sprintory_backend.project.dto;

import java.util.List;
import team.sprintory_backend.project.models.ProjectTemplate;

/** The board is empty at creation: no tasks, active sprint, backlog items or WIP limits. */
public record ProjectBoardResponse(Long projectId, ProjectTemplate template,
                                   boolean backlogEnabled, boolean sprintsEnabled,
                                   boolean wipLimitsEnabled, Long activeSprintId,
                                   List<ProjectBoardColumnResponse> columns) {
    public ProjectBoardResponse {
        columns = List.copyOf(columns);
    }
}
