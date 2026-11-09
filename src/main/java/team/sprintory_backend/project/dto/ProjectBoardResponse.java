package team.sprintory_backend.project.dto;

import java.util.List;
import team.sprintory_backend.project.models.ProjectTemplate;

/**
 * The board: columns with their counts, and for Scrum the active sprint (PMP-40). {@code activeSprint}
 * is null for Kanban and when no sprint is active; {@code activeSprintId} is its id, kept for
 * existing clients.
 */
public record ProjectBoardResponse(Long projectId, ProjectTemplate template,
                                   boolean backlogEnabled, boolean sprintsEnabled,
                                   boolean wipLimitsEnabled, Long activeSprintId,
                                   List<ProjectBoardColumnResponse> columns,
                                   ActiveSprintSummary activeSprint) {
    public ProjectBoardResponse {
        columns = List.copyOf(columns);
    }

    /** Without an active sprint summary (Kanban, tests). */
    public ProjectBoardResponse(Long projectId, ProjectTemplate template, boolean backlogEnabled,
                                boolean sprintsEnabled, boolean wipLimitsEnabled, Long activeSprintId,
                                List<ProjectBoardColumnResponse> columns) {
        this(projectId, template, backlogEnabled, sprintsEnabled, wipLimitsEnabled, activeSprintId, columns, null);
    }
}